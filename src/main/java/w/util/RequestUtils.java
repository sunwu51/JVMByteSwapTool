package w.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Frank
 * @date 2023/12/21 11:43
 */
public class RequestUtils {
    private static final ThreadLocal<Deque<String>> TRACE_ID_CTX = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Map<String, byte[]>> CLASS_NAME_TO_BYTE_CODE = ThreadLocal.withInitial(HashMap::new);

    public static void initRequestCtx(String traceId) {
        Deque<String> traceIds = TRACE_ID_CTX.get();
        traceIds.clear();
        traceIds.push(traceId);
    }

    public static void clearRequestCtx() {
        Deque<String> traceIds = TRACE_ID_CTX.get();
        if (!traceIds.isEmpty()) {
            traceIds.pop();
        }
        if (traceIds.isEmpty()) {
            TRACE_ID_CTX.remove();
            CLASS_NAME_TO_BYTE_CODE.remove();
        }
    }

    public static String getTraceId() {
        return getCurTraceId();
    }

    public static void fillCurThread(String traceId) {
        TRACE_ID_CTX.get().push(traceId);
    }

    public static String getCurTraceId() {
        Deque<String> traceIds = TRACE_ID_CTX.get();
        return traceIds.isEmpty() ? null : traceIds.peek();
    }

}
