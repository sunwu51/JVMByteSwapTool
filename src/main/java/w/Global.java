package w;

import com.fasterxml.jackson.core.JsonProcessingException;
import javassist.ClassPool;
import m3.prettyobject.PrettyFormat;
import m3.prettyobject.PrettyFormatRegistry;
import ognl.*;
import w.core.ExecBundle;
import w.core.MethodId;
import w.core.Retransformer;
import w.core.model.BaseClassTransformer;
import w.core.model.OuterWatchTransformer;
import w.core.model.WatchTransformer;
import w.util.NativeUtils;
import w.util.PrintUtils;
import w.util.RequestUtils;
import w.util.SpringUtils;
import w.util.model.TransformerDesc;
import w.web.message.LogMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;
import w.web.message.WatchMessage;

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
    public static Instrumentation instrumentation;

    public static Map<String, Map<String, List<BaseClassTransformer>>> activeTransformers = new ConcurrentHashMap<>();

    private static Set<NanoWSD.WebSocket> webSockets = new HashSet<>();

    public static void addWs(NanoWSD.WebSocket ws) {
        webSockets.add(ws);
    }

    public static void removeWs(NanoWSD.WebSocket ws) {
        webSockets.remove(ws);
    }

    // 上下文相关的
    public final static ThreadLocal<String> traceIdCtx = new ThreadLocal<>();
    public final static Map<String, Map<MethodId, Retransformer>> traceId2MethodId2Trans = new ConcurrentHashMap<>();

    public final static Map<MethodId, String> methodId2TraceId = new ConcurrentHashMap<>();


    public static int wsPort = 0;

    public static ExecBundle execBundle = null;

    public static ClassPool classPool = ClassPool.getDefault();

    public static void info(Object content) {
        log(1, "" + content);
    }

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

    public static native Object[] getInstances(Class<?> cls);

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

    public static String toString(Object obj) {
        try {
            StringBuilder sb = new StringBuilder();
            PrintUtils.getPrettyFormat().format(obj, sb);
            return sb.toString();
        } catch (Exception e) {
            return "toString error";
        }
    }

    public static String toJson(Object obj) throws JsonProcessingException {
        return PrintUtils.getObjectMapper().writeValueAsString(obj);
    }

    public static ClassLoader getClassLoader() {
        return SpringUtils.getSpringBootClassLoader() == null ? Global.class.getClassLoader() : SpringUtils.getSpringBootClassLoader();
    }

    public static synchronized void record(Class<?> c, BaseClassTransformer transformer) {
        String className = c.getName();
        String classLoader = c.getClassLoader().toString();
        activeTransformers.computeIfAbsent(className, k->new HashMap<>()).computeIfAbsent(classLoader, k->new ArrayList<>()).add(transformer);
    }

    public static synchronized void deleteTransformer(UUID uuid) {
        Set<BaseClassTransformer> set = activeTransformers.values().stream()
                .flatMap(it -> it.values().stream().flatMap(Collection::stream)).collect(Collectors.toSet());

        for (BaseClassTransformer transformer : set) {
            if (transformer.getUuid().equals(uuid)) {
                transformer.setStatus(-1);
                instrumentation.removeTransformer(transformer);
                for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                    if (Objects.equals(c.getName(), transformer.getClassName())) {
                        try {
                            instrumentation.retransformClasses(c);
                        } catch (UnmodifiableClassException e) {
                            Global.log(2, "delete transformer error " + e.getMessage());
                        }
                    }
                }
                Global.info("uninstall transformer " + transformer.desc() + " finished");
            }
        }
        Set<String> delClass = new HashSet<>();
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
    public static synchronized void reset() {
        Set<BaseClassTransformer> set = activeTransformers.values().stream()
                .flatMap(it -> it.values().stream().flatMap(Collection::stream)).collect(Collectors.toSet());
        Set<String> cls = set.stream().map(transformer -> transformer.getClassName()).collect(Collectors.toSet());
        for (BaseClassTransformer transformer : set) {
            instrumentation.removeTransformer(transformer);
        }
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (cls.contains(loadedClass.getName()) && loadedClass.getClassLoader() != null) {
                try {
                    instrumentation.retransformClasses(loadedClass);
                } catch (UnmodifiableClassException e) {
                    log(2, "re transform error " + e.getMessage());
                }
            }
        }
        activeTransformers.clear();
        Global.info("uninstall all transformer finished");
    }

    public static Object ognl(String exp, Object root) throws OgnlException {
        return Ognl.getValue(exp, ognlContext, root);
    }
}
