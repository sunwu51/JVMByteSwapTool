package w.core.asm;

import w.Global;
import w.core.constant.Codes;
import w.util.RequestUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Frank
 * @date 2024/6/30 16:27
 */
public class Tool {
    private static final Map<String, Map<String, String>> OGNL_VARIABLES = new ConcurrentHashMap<>();

    public static void registerOgnlVariables(String uuid, Map<String, String> variables) {
        if (uuid == null) {
            return;
        }
        if (variables == null || variables.isEmpty()) {
            OGNL_VARIABLES.remove(uuid);
            return;
        }
        OGNL_VARIABLES.put(uuid, new LinkedHashMap<>(variables));
    }

    public static void unregisterOgnlVariables(String uuid) {
        if (uuid != null) {
            OGNL_VARIABLES.remove(uuid);
        }
    }

    public static void watchPostProcess(long startTime, int minCost, String uuid, String traceId, String methodSignature, String params, String result, String exception, Object root, String ognl, int printFormat, Object[] req, Object res, Throwable exp, int depthForJson){
        try {
            long cost = System.currentTimeMillis() - startTime;
            if (cost >= minCost) {
                Global.checkCountAndUnload(uuid);
                StringBuilder message = (new StringBuilder()).append(methodSignature)
                        .append(", cost:").append(cost).append("ms, req:").append(params)
                        .append(", res:").append(result).append(", throw:").append(exception)
                        .append(", mdc:").append(getMdcContextMapString(printFormat, depthForJson));
                appendOgnl(message, root, ognl, printFormat, req, res, exp, depthForJson, uuid);
                Global.info(message);
            }
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    public static void outerWatchPostProcess(int line, long startTime, String uuid, String traceId, String methodSignature, String params, String result, String exception, Object root, String ognl, int printFormat, Object[] req, Object res, Throwable exp, int depthForJson) {
        long cost = System.currentTimeMillis() - startTime;
        Global.checkCountAndUnload(uuid);
        RequestUtils.fillCurThread(traceId);
        try {
            StringBuilder message = new StringBuilder(String.format("line: %d, %s, cost: %dms, req: %s, res: %s, throw: %s, mdc: %s", line, methodSignature, cost, params, result, exception, getMdcContextMapString(printFormat, depthForJson)));
            appendOgnl(message, root, ognl, printFormat, req, res, exp, depthForJson, uuid);
            Global.info(message);
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    public static String getOgnlString(Object root, String ognl, int printFormat) {
        return getOgnlString(root, ognl, printFormat, null, null, null, -1, null);
    }

    public static String getOgnlString(Object root, String ognl, int printFormat, Object[] req, Object res, Throwable exp, int depthForJson, String uuid) {
        if (isBlank(ognl)) {
            return null;
        }
        try {
            Map<String, Object> variables = buildVariables(root, req, res, exp, uuid);
            return formatValue(Global.ognl(ognl, root, variables), printFormat, depthForJson);
        } catch (Throwable e) {
            return "ognl error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void appendOgnl(StringBuilder message, Object root, String ognl, int printFormat, Object[] req, Object res, Throwable exp, int depthForJson, String uuid) {
        if (!isBlank(ognl)) {
            message.append(", ognl:").append(getOgnlString(root, ognl, printFormat, req, res, exp, depthForJson, uuid));
        }
    }

    public static String getMdcContextMapString() {
        return getMdcContextMapString(Codes.PRINT_FORMAT_FOR_TO_STRING);
    }

    public static String getMdcContextMapString(int printFormat) {
        return getMdcContextMapString(printFormat, -1);
    }

    public static String getMdcContextMapString(int printFormat, int depthForJson) {
        Object mdc = getMdcContextMap();
        return formatValue(mdc, printFormat, depthForJson);
    }

    private static String formatValue(Object value, int printFormat) {
        return formatValue(value, printFormat, -1);
    }

    private static String formatValue(Object value, int printFormat, int depthForJson) {
        if (printFormat == Codes.PRINT_FORMAT_FOR_TO_JSON) {
            return Global.toJson(value, depthForJson);
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> buildVariables(Object root, Object[] req, Object res, Throwable exp, String uuid) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("req", req);
        variables.put("res", res);
        variables.put("exp", exp);
        Map<String, String> expressions = uuid == null ? null : OGNL_VARIABLES.get(uuid);
        if (expressions == null || expressions.isEmpty()) {
            return variables;
        }
        for (Map.Entry<String, String> entry : expressions.entrySet()) {
            String key = entry.getKey();
            if (isBlank(key)) {
                continue;
            }
            variables.put(key, evalVariable(root, entry.getValue(), variables));
        }
        return variables;
    }

    private static Object evalVariable(Object root, String expression, Map<String, Object> variables) {
        if (isBlank(expression)) {
            return null;
        }
        try {
            return Global.ognl(expression, root, variables);
        } catch (Throwable e) {
            return "ognl variable error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Object getMdcContextMap() {
        try {
            Class<?> mdcClass = Global.getClassLoader().loadClass("org.slf4j.MDC");
            Method getCopyOfContextMap = mdcClass.getMethod("getCopyOfContextMap");
            return getCopyOfContextMap.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
