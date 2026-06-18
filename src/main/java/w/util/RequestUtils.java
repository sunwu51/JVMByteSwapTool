package w.util;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Frank
 * @date 2023/12/21 11:43
 */
public class RequestUtils {
    private static final ThreadLocal<String> TRACE_ID_CTX = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, byte[]>> CLASS_NAME_TO_BYTE_CODE = ThreadLocal.withInitial(HashMap::new);

    public static void initRequestCtx(String traceId) {
        TRACE_ID_CTX.set(traceId);
    }

    public static void clearRequestCtx() {
        TRACE_ID_CTX.remove();
        CLASS_NAME_TO_BYTE_CODE.remove();
    }

    public static String getTraceId() {
        return TRACE_ID_CTX.get();
    }

    public static void fillCurThread(String traceId) {
        initRequestCtx(traceId);
    }

    public static String getCurTraceId() {
        return TRACE_ID_CTX.get();
    }

}
