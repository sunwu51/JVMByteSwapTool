package w.core.asm;

import w.Global;
import w.util.RequestUtils;

/**
 * @author Frank
 * @date 2024/6/30 16:27
 */
public class Tool {
    public static void watchPostProcess(long startTime, int minCost, String uuid, String traceId, String methodSignature, String params, String result, String exception){
        long cost = System.currentTimeMillis() - startTime;
        if (cost >= minCost) {
            Global.checkCountAndUnload(uuid);
            RequestUtils.fillCurThread(traceId);
            Global.info((new StringBuilder()).append(methodSignature)
                    .append(", cost:").append(cost).append("ms, req:").append(params)
                    .append(", res:").append(result).append(", throw:").append(exception));
            RequestUtils.clearRequestCtx();
        }
    }

    public static void outerWatchPostProcess(int line, long startTime, String uuid, String traceId, String methodSignature, String params, String result, String exception) {
        long cost = System.currentTimeMillis() - startTime;
        Global.checkCountAndUnload(uuid);
        RequestUtils.fillCurThread(traceId);
        Global.info(String.format("line: %d, %s, cost: %dms, req: %s, res: %s, throw: %s", line, methodSignature, cost, params, result, exception));
        RequestUtils.clearRequestCtx();
    }
}
