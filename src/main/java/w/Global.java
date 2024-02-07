package w;

import com.fasterxml.jackson.core.JsonProcessingException;
import javassist.ClassPool;
import lombok.Getter;
import ognl.*;
import w.core.model.BaseClassTransformer;
import w.util.NativeUtils;
import w.util.PrintUtils;
import w.util.RequestUtils;
import w.util.SpringUtils;
import w.web.message.LogMessage;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Global {
    /**
     * JVM instrumentation, will set at start up, won't be null
     */
    public static Instrumentation instrumentation;

    public static Map<String, Set<Class<?>>> allLoadedClasses = new ConcurrentHashMap<>();

    /**
     * The WebSocket Server port, will set at start up, default to be 18000
     */
    public static int wsPort = 0;

    /**
     * The Javassist ClassPool used in this project.
     */
    public static ClassPool classPool = ClassPool.getDefault();

    /**
     * Connected websocket set, used to send broadcast message
     */
    private static Set<NanoWSD.WebSocket> webSockets = new HashSet<>();

    /**
     * All transformers that added by this project, include all kinds of status
     */
    public static Set<BaseClassTransformer> transformers = new HashSet<>();

    /**
     * Active transformer cache where the transformer status should be 1, ClassName - ClassLoader - TransformerList
     */
    public static Map<String, Map<String, List<BaseClassTransformer>>> activeTransformers = new ConcurrentHashMap<>();


    /**
     * OgnlContext inited at static code block
     */
    public static OgnlContext ognlContext;

    static {
        classPool.importPackage("java.util");
        String lib = null;
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        if (os.contains("win") && (arch.equals("x86_64") || arch.equals("amd64"))) {
            lib = "w_amd64.dll";
        } else if (os.contains("linux") && (arch.equals("x86_64") || arch.equals("amd64"))) {
            lib = "w_amd64.so";
        } else if (os.contains("mac")) {
            if (arch.equals("x86_64") || arch.equals("amd64")) {
                lib = "w_amd64.dylib";
            } else if (arch.equals("aarch64")) {
                lib = "w_aarch64.dylib";
            }
        }
        if (lib == null){
            System.err.println("os " + os +" not support");
            throw new RuntimeException("os " + os +" not support");
        }

        try {
            NativeUtils.loadLibraryFromJar("/" + lib);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ognlContext = new OgnlContext(
                new ClassResolver() {
                    @Override
                    public Class classForName(String s, Map map) throws ClassNotFoundException {
                        try {
                            return Global.getClassLoader().loadClass(s);
                        } catch (Exception e) {
                            throw new ClassNotFoundException(s);
                        }
                    }
                },
                new DefaultTypeConverter(),
                new DefaultMemberAccess(true)
        );
    }


    /**
     * Native method to get instances by class, the return object array length at most 100.
     * This method only for get the spring context in this project.
     * @param cls
     * @return
     */
    public static native Object[] getInstances(Class<?> cls);


    /**
     * Add websocket client to set
     * @param ws
     */
    public static void addWs(NanoWSD.WebSocket ws) {
        webSockets.add(ws);
    }

    /**
     * Remove websocket
     * @param ws
     */
    public static void removeWs(NanoWSD.WebSocket ws) {
        webSockets.remove(ws);
    }

    /**
     * Print info log, and broadcast to all websocket client
     * @param content
     */
    public static void info(Object content) {
        log(1, "" + content);
    }

    /**
     * Print error log, and broadcast to all websocket client
     * @param content
     */
    public static void error(Object content) {
        log(2, "" + content);
    }

    /**
     * To pretty string, by m3.prettyobjct.*
     * @param obj
     * @return
     */
    public static String toString(Object obj) {
        try {
            StringBuilder sb = new StringBuilder();
            PrintUtils.getPrettyFormat().format(obj, sb);
            return sb.toString();
        } catch (Exception e) {
            return "toString error";
        }
    }

    /**
     * To json string, by Jackson
     * @param obj
     * @return
     * @throws JsonProcessingException
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return PrintUtils.getObjectMapper().writeValueAsString(obj);
    }

    /**
     * Get the major class loader, if java process is a spring uber jar, will be org.springframework.boot.loader.LaunchedURLClassLoader
     * Else will be AppClassLoader
     * @return
     */
    public static ClassLoader getClassLoader() {
        return SpringUtils.getSpringBootClassLoader() == null ? Global.class.getClassLoader() : SpringUtils.getSpringBootClassLoader();
    }

    /**
     * Add a transformer, the transformer will be active after re transform
     * @param transformer
     */
    public static synchronized void addTransformer(BaseClassTransformer transformer) {
        instrumentation.addTransformer(transformer, true);
        transformers.add(transformer);
    }

    /**
     * Re transform and add active transformer
     * @param c
     * @param transformer
     * @throws UnmodifiableClassException
     */
    public static synchronized void addActiveTransformer(Class<?> c, BaseClassTransformer transformer) throws UnmodifiableClassException {
        instrumentation.retransformClasses(c);
        String className = c.getName();
        String classLoader = c.getClassLoader().toString();
        activeTransformers.computeIfAbsent(className, k->new HashMap<>()).computeIfAbsent(classLoader, k->new ArrayList<>()).add(transformer);
    }

    /**
     * Remove transformer By uuid
     * @param uuid
     */
    public static synchronized void deleteTransformer(UUID uuid) {
        Set<String> delClass = new HashSet<>();
        transformers.removeIf(it -> {
            if (it.getUuid().equals(uuid)) {
                it.setStatus(-1);
                instrumentation.removeTransformer(it);
                delClass.add(it.getClassName());
                return true;
            }
            return false;
        });
        activeTransformers.forEach((c, m) -> {
            m.forEach((l, list) -> {
                list.removeIf(transformer -> transformer.getStatus() == -1);
            });
            Set<String> del = new HashSet<>();
            for (Map.Entry<String, List<BaseClassTransformer>> stringListEntry : m.entrySet()) {
                if (stringListEntry.getValue().isEmpty()) {
                    del.add(stringListEntry.getKey());
                }
            }
            for (String s : del) {
                m.remove(s);
            }
            if (m.isEmpty()) {
                delClass.add(c);
            }
        });
        for (String aClass : delClass) {
            activeTransformers.remove(aClass);
        }
    }

    /**
     * Remove all transformer
     */
    public static synchronized void reset() {
        Set<String> cls = transformers.stream().map(BaseClassTransformer::getClassName).collect(Collectors.toSet());
        for (BaseClassTransformer transformer : transformers) {
            instrumentation.removeTransformer(transformer);
        }
        for (String cl : cls) {
            for (Class<?> aClass : Global.allLoadedClasses.getOrDefault(cl, new HashSet<>())) {
                if (aClass.getClassLoader() != null) {
                    try {
                        instrumentation.retransformClasses(aClass);
                    } catch (UnmodifiableClassException e) {
                        log(2, "re transform error " + e.getMessage());
                    }
                }
            }

        }
        transformers.clear();
        activeTransformers.clear();
        Global.info("uninstall all transformer finished");
    }

    /**
     * Run ognl using major classloader
     * @param exp
     * @param root
     * @return
     * @throws OgnlException
     */
    public static Object ognl(String exp, Object root) throws OgnlException {
        return Ognl.getValue(exp, ognlContext, root);
    }

    /**
     * return the advised target if the object is instance of Advised
     * @param bean
     * @return
     */
    public static Object beanTarget(Object bean) {
        try {
            return ognl("#root.getTargetSource().getTarget()", bean);
        } catch (Exception e) {
            return bean;
        }
    }



    // 1 info 2 error
    private static void log(int level, String content) {
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

    private static synchronized void send(String content) {
        Iterator<NanoWSD.WebSocket> it = webSockets.iterator();
        while (it.hasNext()) {
            NanoWSD.WebSocket ws = it.next();
            if (ws != null && ws.isOpen()) {
                try {
                    LogMessage message = new LogMessage();
                    message.setId(RequestUtils.getCurTraceId());
                    message.setContent(content);
                    ws.send(toJson(message));
                } catch (IOException e) {
                    System.err.println("send message error" + e);
                }
            } else {
                it.remove();
            }
        }
    }

    public synchronized static void fillLoadedClasses() {
        for (Class cls : instrumentation.getAllLoadedClasses()) {
            String name = cls.getName();
            allLoadedClasses.computeIfAbsent(name, k -> new HashSet<>())
                    .add(cls);
        }
    }
}
