package w.core.diagnostic;

import w.Global;
import w.bridge.DiagnosticListener;
import w.core.asm.Tool;
import w.core.model.OuterWatchTransformer;
import w.core.model.TraceTransformer;
import w.util.RequestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent-side diagnostic implementation. Tokens retain stable configs across reset/delete.
 */
public final class AgentDiagnosticListener implements DiagnosticListener {
    private final Map<String, WatchConfig> watchConfigs = new ConcurrentHashMap<>();
    private final Map<String, OuterWatchConfig> outerConfigs = new ConcurrentHashMap<>();
    private final Map<String, TraceConfig> traceConfigs = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, TraceConfig>> activeTraceConfigs =
            ThreadLocal.withInitial(HashMap::new);

    @Override
    public void info(Object message) {
        Global.info(message);
    }

    @Override
    public void error(Object message, Throwable throwable) {
        if (throwable == null) {
            Global.error(message);
        } else {
            Global.error(message, throwable);
        }
    }

    public void register(WatchConfig config) {
        watchConfigs.put(config.getId(), config);
    }

    public void register(OuterWatchConfig config) {
        outerConfigs.put(config.getId(), config);
    }

    public void register(TraceConfig config) {
        traceConfigs.put(config.getId(), config);
    }

    public void unregister(String id) {
        watchConfigs.remove(id);
        outerConfigs.remove(id);
        traceConfigs.remove(id);
        Tool.unregisterOgnlVariables(id);
    }

    public boolean contains(String id) {
        return watchConfigs.containsKey(id)
                || outerConfigs.containsKey(id)
                || traceConfigs.containsKey(id);
    }

    @Override
    public Object watchEnter(String id) {
        WatchConfig config = watchConfigs.get(id);
        if (config == null) {
            return null;
        }
        RequestUtils.fillCurThread(config.getTraceId());
        return new Token(config);
    }

    @Override
    public void watchExit(Object opaqueToken, long start, Object target, Object[] args,
                          Object result, Throwable throwable) {
        if (!(opaqueToken instanceof Token) || !(((Token) opaqueToken).config instanceof WatchConfig)) {
            return;
        }
        WatchConfig config = (WatchConfig) ((Token) opaqueToken).config;
        try {
            long cost = System.currentTimeMillis() - start;
            if (cost < config.getMinCost()) {
                return;
            }
            Global.checkCountAndUnload(config.getId());
            StringBuilder message = new StringBuilder(config.getMethodSignature())
                    .append(", cost:").append(cost).append("ms, req:")
                    .append(formatArgs(args, config.getPrintFormat(), config.getDepthForJson()))
                    .append(", res:").append(format(result, config.getPrintFormat(), config.getDepthForJson()))
                    .append(", throw:").append(format(throwable, config.getPrintFormat(), config.getDepthForJson()))
                    .append(", mdc:")
                    .append(Tool.getMdcContextMapString(config.getPrintFormat(), config.getDepthForJson()));
            if (!isBlank(config.getOgnl())) {
                message.append(", ognl:").append(Tool.getOgnlString(
                        target, config.getOgnl(), config.getPrintFormat(), args, result, throwable,
                        config.getDepthForJson(), config.getVariables()));
            }
            Global.info(message);
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    @Override
    public void outerEnter(String id) {
        OuterWatchTransformer.enterOuterWatch(id);
    }

    @Override
    public void outerExit(String id) {
        OuterWatchTransformer.exitOuterWatch(id);
    }

    @Override
    public boolean outerShouldRecord(String id) {
        return OuterWatchTransformer.shouldRecord(id);
    }

    @Override
    public Object outerCallEnter(String id) {
        OuterWatchConfig config = outerConfigs.get(id);
        if (config == null) {
            return null;
        }
        RequestUtils.fillCurThread(config.getTraceId());
        return new Token(config);
    }

    @Override
    public void outerCallExit(Object opaqueToken, int line, long start, Object target,
                              Object[] args, Object result, Throwable throwable) {
        if (!(opaqueToken instanceof Token) || !(((Token) opaqueToken).config instanceof OuterWatchConfig)) {
            return;
        }
        OuterWatchConfig config = (OuterWatchConfig) ((Token) opaqueToken).config;
        try {
            long cost = System.currentTimeMillis() - start;
            Global.checkCountAndUnload(config.getId());
            StringBuilder message = new StringBuilder("line: ").append(line)
                    .append(", ").append(config.getMethodSignature())
                    .append(", cost: ").append(cost).append("ms, req: ")
                    .append(formatArgs(args, config.getPrintFormat(), config.getDepthForJson()))
                    .append(", res: ")
                    .append(throwable == null
                            ? format(result, config.getPrintFormat(), config.getDepthForJson()) : null)
                    .append(", throw: ")
                    .append(throwable == null
                            ? null : format(throwable, config.getPrintFormat(), config.getDepthForJson()))
                    .append(", mdc: ")
                    .append(Tool.getMdcContextMapString(config.getPrintFormat(), config.getDepthForJson()));
            if (!isBlank(config.getOgnl())) {
                message.append(", ognl:").append(Tool.getOgnlString(
                        target, config.getOgnl(), config.getPrintFormat(), args, result, throwable,
                        config.getDepthForJson(), config.getVariables()));
            }
            Global.info(message);
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    @Override
    public Object traceEnter(String id) {
        TraceConfig config = traceConfigs.get(id);
        if (config == null) {
            config = activeTraceConfigs.get().get(id);
            if (config == null) {
                return null;
            }
        }
        activeTraceConfigs.get().putIfAbsent(id, config);
        TraceTransformer.enterTrace(id);
        return new Token(config);
    }

    @Override
    public void traceRecursive(String id) {
        TraceTransformer.recursiveRecord(id);
    }

    @Override
    public void traceSubCall(String id, String key, long duration) {
        TraceTransformer.subTrace(id, key, duration);
    }

    @Override
    public void traceExit(Object opaqueToken, long start) {
        if (!(opaqueToken instanceof Token) || !(((Token) opaqueToken).config instanceof TraceConfig)) {
            return;
        }
        TraceConfig config = (TraceConfig) ((Token) opaqueToken).config;
        boolean finished = false;
        try {
            finished = TraceTransformer.traceSummary(start, config.getMinCost(), config.getId(),
                    config.getTraceId(), config.getMethodSignature(), config.isIgnoreZero());
        } finally {
            if (finished || !TraceTransformer.hasTraceContext(config.getId())) {
                Map<String, TraceConfig> active = activeTraceConfigs.get();
                active.remove(config.getId());
                if (active.isEmpty()) {
                    activeTraceConfigs.remove();
                }
            }
        }
    }

    private String formatArgs(Object[] args, int printFormat, int depthForJson) {
        if (printFormat == 2) {
            return Global.toJson(args, depthForJson);
        }
        try {
            return Arrays.toString(args);
        } catch (Throwable e) {
            return formatError(e);
        }
    }

    private String format(Object value, int printFormat, int depthForJson) {
        if (value == null) {
            return "null";
        }
        if (printFormat == 2) {
            return Global.toJson(value, depthForJson);
        }
        try {
            return String.valueOf(value);
        } catch (Throwable e) {
            return formatError(e);
        }
    }

    private String formatError(Throwable e) {
        return "render error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Token {
        private final Object config;

        private Token(Object config) {
            this.config = config;
        }
    }
}
