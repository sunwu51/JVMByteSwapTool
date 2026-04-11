package w.util;

import fi.iki.elonen.NanoWSD;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Frank
 * @date 2023/12/21 11:43
 */
public class RequestUtils {
    private static final ThreadLocal<NanoWSD.WebSocket> SOCKET_CTX = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID_CTX = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, byte[]>> CLASS_NAME_TO_BYTE_CODE = ThreadLocal.withInitial(HashMap::new);
    public static final Map<String, NanoWSD.WebSocket> TRACE_ID_TO_WS = new ConcurrentHashMap<>();

    public static void initRequestCtx(NanoWSD.WebSocket ws, String traceId) {
        SOCKET_CTX.set(ws);
        TRACE_ID_CTX.set(traceId);
        if (traceId != null && ws != null) {
            TRACE_ID_TO_WS.put(traceId, ws);
        }
    }

    public static void clearRequestCtx() {
        SOCKET_CTX.remove();
        TRACE_ID_CTX.remove();
        CLASS_NAME_TO_BYTE_CODE.remove();
    }

    public static String getTraceId() {
        return TRACE_ID_CTX.get();
    }

    public static NanoWSD.WebSocket getCurWs() {
        return SOCKET_CTX.get();
    }

    public static void fillCurThread(String traceId) {
        initRequestCtx(TRACE_ID_TO_WS.get(traceId), traceId);
    }

    public static String getCurTraceId() {
        return TRACE_ID_CTX.get();
    }

    public static NanoWSD.WebSocket getWsByTraceId(String traceId) {
        return TRACE_ID_TO_WS.get(traceId);
    }

}
