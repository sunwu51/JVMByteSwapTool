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
    public static void watchPostProcess(long startTime, int minCost, String uuid, String traceId, String methodSignature, String params, String result, String exception, int printFormat){
        try {
            long cost = System.currentTimeMillis() - startTime;
            if (cost >= minCost) {
                Global.checkCountAndUnload(uuid);
                Global.info((new StringBuilder()).append(methodSignature)
                        .append(", cost:").append(cost).append("ms, req:").append(params)
                        .append(", res:").append(result).append(", throw:").append(exception)
                        .append(", mdc:").append(getMdcContextMapString(printFormat)));
            }
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    public static void outerWatchPostProcess(int line, long startTime, String uuid, String traceId, String methodSignature, String params, String result, String exception, int printFormat) {
        long cost = System.currentTimeMillis() - startTime;
        Global.checkCountAndUnload(uuid);
        RequestUtils.fillCurThread(traceId);
        Global.info(String.format("line: %d, %s, cost: %dms, req: %s, res: %s, throw: %s, mdc: %s", line, methodSignature, cost, params, result, exception, getMdcContextMapString(printFormat)));
        RequestUtils.clearRequestCtx();
    }

    public static String getMdcContextMapString() {
        return getMdcContextMapString(Codes.PRINT_FORMAT_FOR_TO_STRING);
    }

    public static String getMdcContextMapString(int printFormat) {
        Object mdc = getMdcContextMap();
        if (printFormat == Codes.PRINT_FORMAT_FOR_TO_JSON) {
            return Global.toJson(mdc);
        }
        return String.valueOf(mdc);
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
