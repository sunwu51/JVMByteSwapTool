package w;

import javassist.ClassPool;
import sun.reflect.Reflection;
import w.core.ExecBundle;
import w.core.MethodId;
import w.core.Retransformer;
import w.web.message.LogMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Global {
    public static Instrumentation instrumentation;

    public static ClassLoader springBootCl;

    public static Object springApplicationContext;

    public final static ThreadLocal<NanoWSD.WebSocket> socketCtx = new ThreadLocal<>();
    public final static ThreadLocal<String> traceIdCtx = new ThreadLocal<>();

    public final static ObjectMapper objectMapper = new ObjectMapper();

    public final static Map<String, NanoWSD.WebSocket> socketMap = new ConcurrentHashMap<>();

    public final static Map<String, Map<MethodId, Retransformer>> traceId2MethodId2Trans = new ConcurrentHashMap<>();

    public final static Map<MethodId, String> methodId2TraceId = new ConcurrentHashMap<>();

    public static ThreadLocal<Map<String, Set<ClassLoader>>> classToLoader = ThreadLocal.withInitial(HashMap::new);

    public static int wsPort = 0;

    public static ExecBundle execBundle = null;

    public static ClassPool classPool = ClassPool.getDefault();

    public static void info(String content) {
        log(1, content);
    }

    // 1 info 2 error
    public static void log(int level, String content) {
        Logger log = Logger.getLogger(Thread.currentThread().getStackTrace()[2].getClassName());
        switch (level) {
            case 1:
                log.log(Level.INFO, "[info]" + content);
                break;
            case 2:
            default:
                log.log(Level.SEVERE, "[error]" + content);
        }
        send(content);
    }
    private static void send(String content) {
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
                System.err.println("send message error" + e);
            }
        }
    }

    public static ClassLoader getClassLoader() {
        return springBootCl == null ? Global.class.getClassLoader() : springBootCl;
    }
}
