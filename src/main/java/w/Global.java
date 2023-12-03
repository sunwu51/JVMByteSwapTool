package w;

import javassist.ClassPool;
import w.core.MethodId;
import w.core.Retransformer;
import w.web.message.LogMessage;
import w.web.message.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class Global {
    public static Instrumentation instrumentation;

    public static Object springApplicationContext;

    public final static ThreadLocal<NanoWSD.WebSocket> socketCtx = new ThreadLocal<>();
    public final static ThreadLocal<String> traceIdCtx = new ThreadLocal<>();

    public final static ObjectMapper objectMapper = new ObjectMapper();

    public final static Map<String, NanoWSD.WebSocket> socketMap = new ConcurrentHashMap<>();

    public final static Map<String, Map<MethodId, Retransformer>> traceId2MethodId2Trans = new ConcurrentHashMap<>();

    public final static Map<MethodId, String> methodId2TraceId = new ConcurrentHashMap<>();

    public static int wsPort = 0;

    public static ClassPool classPool = ClassPool.getDefault();

    public static void info(String content) {
        log.info(content);

        if (socketCtx.get() == null && traceIdCtx.get() != null) {
            NanoWSD.WebSocket ws = socketMap.get(traceIdCtx.get());
            socketCtx.set(ws);
        }

        if (socketCtx.get() != null && socketCtx.get().isOpen()) {
            try {
                LogMessage message = new LogMessage();
                message.setId(traceIdCtx.get());
                message.setContent(content);
                socketCtx.get().send(objectMapper.writeValueAsString(message));
            } catch (IOException e) {
                log.error("send message error", e);
            }
        }
    }

    public static void exec() {
        info("exec....");
    }
}
