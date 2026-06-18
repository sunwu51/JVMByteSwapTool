package w.core.asm;

import w.Global;
import w.core.constant.Codes;
import w.util.RequestUtils;

import java.lang.reflect.Method;

/**
 * @author Frank
 * @date 2024/6/30 16:27
 */
public class Tool {
    public static void watchPostProcess(long startTime, int minCost, String uuid, String traceId, String methodSignature, String params, String result, String exception, Object root, String ognl, int printFormat){
        try {
            long cost = System.currentTimeMillis() - startTime;
            if (cost >= minCost) {
                Global.checkCountAndUnload(uuid);
                StringBuilder message = (new StringBuilder()).append(methodSignature)
                        .append(", cost:").append(cost).append("ms, req:").append(params)
                        .append(", res:").append(result).append(", throw:").append(exception)
                        .append(", mdc:").append(getMdcContextMapString(printFormat));
                appendOgnl(message, root, ognl, printFormat);
                Global.info(message);
            }
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    public static void outerWatchPostProcess(int line, long startTime, String uuid, String traceId, String methodSignature, String params, String result, String exception, Object root, String ognl, int printFormat) {
        long cost = System.currentTimeMillis() - startTime;
        Global.checkCountAndUnload(uuid);
        RequestUtils.fillCurThread(traceId);
        StringBuilder message = new StringBuilder(String.format("line: %d, %s, cost: %dms, req: %s, res: %s, throw: %s, mdc: %s", line, methodSignature, cost, params, result, exception, getMdcContextMapString(printFormat)));
        appendOgnl(message, root, ognl, printFormat);
        Global.info(message);
        RequestUtils.clearRequestCtx();
    }

    public static String getOgnlString(Object root, String ognl, int printFormat) {
        if (isBlank(ognl)) {
            return null;
        }
        try {
            return formatValue(Global.ognl(ognl, root), printFormat);
        } catch (Throwable e) {
            return "ognl error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void appendOgnl(StringBuilder message, Object root, String ognl, int printFormat) {
        if (!isBlank(ognl)) {
            message.append(", ognl:").append(getOgnlString(root, ognl, printFormat));
        }
    }

    public static String getMdcContextMapString() {
        return getMdcContextMapString(Codes.PRINT_FORMAT_FOR_TO_STRING);
    }

    public static String getMdcContextMapString(int printFormat) {
        Object mdc = getMdcContextMap();
        return formatValue(mdc, printFormat);
    }

    private static String formatValue(Object value, int printFormat) {
        if (printFormat == Codes.PRINT_FORMAT_FOR_TO_JSON) {
            return Global.toJson(value);
        }
        return String.valueOf(value);
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
