package w.core.watch;

import w.Global;
import w.bridge.WatchListener;
import w.core.asm.Tool;
import w.util.RequestUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent-side implementation. Token captures a stable config for concurrent reset/delete.
 */
public final class AgentWatchListener implements WatchListener {
    private final Map<String, WatchConfig> configs = new ConcurrentHashMap<>();

    public void register(WatchConfig config) {
        configs.put(config.getId(), config);
        Tool.registerOgnlVariables(config.getId(), config.getVariables());
    }

    public void unregister(String id) {
        configs.remove(id);
        Tool.unregisterOgnlVariables(id);
    }

    public boolean contains(String id) {
        return configs.containsKey(id);
    }

    @Override
    public Object enter(String id) {
        WatchConfig config = configs.get(id);
        if (config == null) {
            return null;
        }
        Token token = new Token(config);
        RequestUtils.fillCurThread(config.getTraceId());
        return token;
    }

    @Override
    public void exit(Object opaqueToken, long start, Object target, Object[] args,
                     Object result, Throwable throwable) {
        if (!(opaqueToken instanceof Token)) {
            return;
        }
        Token token = (Token) opaqueToken;
        try {
            WatchConfig config = token.config;
            long cost = System.currentTimeMillis() - start;
            if (cost < config.getMinCost()) {
                return;
            }
            Global.checkCountAndUnload(config.getId());
            StringBuilder message = new StringBuilder(config.getMethodSignature())
                    .append(", cost:").append(cost).append("ms, req:")
                    .append(formatArgs(args, config))
                    .append(", res:").append(format(result, config))
                    .append(", throw:").append(format(throwable, config))
                    .append(", mdc:")
                    .append(Tool.getMdcContextMapString(config.getPrintFormat(), config.getDepthForJson()));
            if (!isBlank(config.getOgnl())) {
                message.append(", ognl:").append(Tool.getOgnlString(
                        target,
                        config.getOgnl(),
                        config.getPrintFormat(),
                        args,
                        result,
                        throwable,
                        config.getDepthForJson(),
                        config.getVariables()));
            }
            Global.info(message);
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    private String formatArgs(Object[] args, WatchConfig config) {
        if (config.getPrintFormat() == 2) {
            return Global.toJson(args, config.getDepthForJson());
        }
        try {
            return Arrays.toString(args);
        } catch (Throwable e) {
            return formatError(e);
        }
    }

    private String format(Object value, WatchConfig config) {
        if (value == null) {
            return "null";
        }
        if (config.getPrintFormat() == 2) {
            return Global.toJson(value, config.getDepthForJson());
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
        private final WatchConfig config;

        private Token(WatchConfig config) {
            this.config = config;
        }
    }
}
