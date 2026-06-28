package w;

import com.alibaba.fastjson2.JSON;
import javassist.ClassPool;
import ognl.DefaultMemberAccess;
import ognl.DefaultTypeConverter;
import ognl.Ognl;
import ognl.OgnlContext;
import ognl.OgnlException;
import w.core.JsonBridgeBundle;
import w.core.model.BaseClassTransformer;
import w.core.model.DecompileTransformer;
import w.util.NativeUtils;
import w.util.RequestUtils;
import w.util.SpringUtils;
import w.web.message.LogMessage;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
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
     * Whether set the java flag -Xverify:none
     */
    public static boolean nonVerifying;

    /**
     * classes and innerJar will unpack to a temp dir
     */
    public static String tempCompileClassPath;

    /**
     * class path list
     */
    public static Set<String> classPaths = new HashSet<>();

    /**
     * The Javassist ClassPool used in this project.
     */
    public static ClassPool classPool = ClassPool.getDefault();

    private static Set<BlockingQueue<String>> logSubscribers = new CopyOnWriteArraySet<>();
    private static final Object logHistoryLock = new Object();
    private static final int LOG_HISTORY_MAX_ENTRIES = 10000;
    private static final long LOG_HISTORY_MAX_BYTES = 100L * 1024L * 1024L;
    private static final long LOG_READ_MAX_TIMEOUT_MS = 30000L;
    private static final int LOG_READ_DEFAULT_MAX_LINES = 100;
    private static final int LOG_READ_MAX_LINES = 500;
    private static final Deque<StoredLog> logHistory = new ArrayDeque<>();
    private static long logHistoryBytes = 0;

    /**
     * All transformers that added by this project, include all kinds of status
     */
    public static Set<BaseClassTransformer> transformers = new HashSet<>();

    /**
     * Active transformer cache where the transformer status should be 1, ClassName - ClassLoader - TransformerList
     */
    public static Map<String, Map<String, List<BaseClassTransformer>>> activeTransformers = new ConcurrentHashMap<>();

    /**
     * Transformer hitCounter, for watch trace the transformer effects 50 times at most by default.
     * Change the default value by environment variable $HIT_COUNT
     */
    public static Map<String, AtomicInteger> hitCounter = new ConcurrentHashMap<>();

    /**
     * A process-wide diagnostic stash for objects captured from watch/ognl/exec/eval.
     * Values are strongly referenced until removed or cleared.
     */
    public static final Map<String, Object> stash = new ConcurrentHashMap<>();


    public static Set<String> ignoreTraceMethods = new HashSet<String>() {{
        add("<init>");
        add("toString");
        add("append");
    }};

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
        } else if (os.contains("linux")) {
            if (arch.equals("x86_64") || arch.equals("amd64")) {
                lib = "w_amd64.so";
            } else if (arch.equals("aarch64")) {
                lib = "w_aarch64.so";
            }
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
                (s, map) -> {
                    try {
                        return Global.getClassLoader().loadClass(s);
                    } catch (Exception e) {
                        throw new ClassNotFoundException(s);
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


    public static void addLogSubscriber(BlockingQueue<String> subscriber) {
        logSubscribers.add(subscriber);
        synchronized (logHistoryLock) {
            for (StoredLog message : logHistory) {
                subscriber.offer(message.json);
            }
        }
    }

    public static void removeLogSubscriber(BlockingQueue<String> subscriber) {
        logSubscribers.remove(subscriber);
    }

    public static int logSubscriberCount() {
        return logSubscribers.size();
    }

    public static List<Map<String, Object>> readLogs(String id, long since, int maxLines, long timeoutMs) {
        String normalizedId = id == null ? "" : id.trim();
        long normalizedTimeout = Math.max(0, Math.min(timeoutMs, LOG_READ_MAX_TIMEOUT_MS));
        long deadline = System.currentTimeMillis() + normalizedTimeout;
        synchronized (logHistoryLock) {
            List<Map<String, Object>> logs = filterLogs(normalizedId, since, maxLines);
            while (logs.isEmpty() && normalizedTimeout > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    logHistoryLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                logs = filterLogs(normalizedId, since, maxLines);
            }
            return logs;
        }
    }

    /**
     * Print debug log, and broadcast to all websocket client
     * @param content
     */
    public static void debug(Object content) {
        log(0, "" + content);
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
     * Print error log, and broadcast to all websocket client
     * @param content
     * @param e
     */
    public static void error(Object content, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String stackTraceString = sw.toString();
        log(2, content + "\n" + stackTraceString);
    }


    /**
     * To json string, by Fastjson
     * @param obj
     * @return
     */
    public static String toJson(Object obj) {
        return toJson(obj, -1);
    }

    public static String toJson(Object obj, int maxDepth) {
        try {
            return JsonBridgeBundle.toJson(obj, maxDepth);
        } catch (Throwable e) {
            Logger.getLogger(Global.class.getName())
                    .log(Level.WARNING, "toJson error: {0}: {1}", new Object[]{e.getClass().getSimpleName(), e.getMessage()});
            return "toJson error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
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
        if (!isModifiableClass(c)) {
            throw new UnmodifiableClassException(c.getName());
        }
        String className = c.getName();
        String classLoader = c.getClassLoader().toString();
        activeTransformers.computeIfAbsent(className, k->new HashMap<>()).computeIfAbsent(classLoader, k->new ArrayList<>()).add(transformer);
        instrumentation.retransformClasses(c);
    }

    public static boolean isModifiableClass(Class<?> clazz) {
        return clazz != null
                && instrumentation != null
                && instrumentation.isModifiableClass(clazz);
    }


    /**
     * Remove transformer By uuid
     * @param uuid
     */
    public static synchronized void deleteTransformer(UUID uuid) {
        info("Deleting transformer " + uuid);
        Set<String> delClass = new HashSet<>();
        transformers.removeIf(it -> {
            if (it.getUuid().equals(uuid)) {
                it.setStatus(-1);
                it.clear();
                instrumentation.removeTransformer(it);
                Set<Class<?>> effectedClasses = new HashSet<>();
                effectedClasses.addAll(allLoadedClasses.getOrDefault(it.getClassName(), new HashSet<>()));
                for (String extraClassName : it.getExtraClassNames()) {
                    effectedClasses.addAll(allLoadedClasses.getOrDefault(extraClassName, new HashSet<>()));
                }
                if (it instanceof DecompileTransformer) {
                    allLoadedClasses.keySet().stream()
                            .filter(k -> k.startsWith(it.getClassName() + "$"))
                            .forEach(k -> effectedClasses.addAll(allLoadedClasses.getOrDefault(k, new HashSet<>())));
                }

                for (Class<?> aClass : effectedClasses) {
                    if (!isModifiableClass(aClass)) {
                        debug("skip unmodifiable class on delete retransform: " + aClass.getName());
                        continue;
                    }
                    try {
                        instrumentation.retransformClasses(aClass);
                    } catch (Exception e) {
                        Global.error("delete re transform error:", e);
                    }
                }
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
        info("Deleted transformer " + uuid);
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
                try {
                    instrumentation.retransformClasses(aClass);
                } catch (Exception e) {
                    Global.error("reset re transform error: ", e);
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
        return ognl(exp, root, null);
    }

    public static Object ognl(String exp, Object root, Map<String, Object> variables) throws OgnlException {
        OgnlContext context = newOgnlContext();
        if (variables != null) {
            context.putAll(variables);
        }
        return Ognl.getValue(exp, context, root);
    }

    public static Object stash(String key) {
        return stash.get(key);
    }

    public static Object stash(String key, Object value) {
        stash.put(key, value);
        return value;
    }

    public static Object unstash(String key) {
        return stash.remove(key);
    }

    public static void clearStash() {
        stash.clear();
    }

    /**
     * Dump ThreadLocal value summaries from the current thread for watch/ognl diagnostics.
     * On JDK 9+, this tries to open java.lang to this module before reflective access.
     * Example OGNL: @w.Global@threadLocals()
     * @return ThreadLocal entries grouped by threadLocal and inheritableThreadLocal
     */
    public static Map<String, Object> threadLocals() {
        return threadLocals(Thread.currentThread());
    }

    public static Map<String, Object> threadLocals(Thread thread) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread", thread == null ? null : thread.getName());
        result.put("threadId", thread == null ? null : thread.getId());
        result.put("threadLocal", dumpThreadLocalMap(thread, "threadLocals"));
        result.put("inheritableThreadLocal", dumpThreadLocalMap(thread, "inheritableThreadLocals"));
        return result;
    }

    public static List<Map<String, Object>> findAssignableClasses(String className) {
        if (instrumentation != null) {
            fillLoadedClasses();
        }
        Set<Class<?>> targets = allLoadedClasses.getOrDefault(className, new HashSet<>());
        List<Map<String, Object>> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> target : targets) {
            for (Set<Class<?>> candidates : allLoadedClasses.values()) {
                for (Class<?> candidate : candidates) {
                    if (candidate == null || candidate.equals(target)) {
                        continue;
                    }
                    try {
                        if (!target.isAssignableFrom(candidate)) {
                            continue;
                        }
                        String key = classLoaderName(target.getClassLoader()) + "|"
                                + candidate.getName() + "|" + classLoaderName(candidate.getClassLoader());
                        if (!seen.add(key)) {
                            continue;
                        }
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("targetClassName", target.getName());
                        item.put("targetClassLoader", classLoaderName(target.getClassLoader()));
                        item.put("className", candidate.getName());
                        item.put("classLoader", classLoaderName(candidate.getClassLoader()));
                        matches.add(item);
                    } catch (Throwable e) {
                        debug("find assignable class skip " + candidate.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        return matches;
    }

    private static String classLoaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }

    private static List<Map<String, Object>> dumpThreadLocalMap(Thread thread, String fieldName) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (thread == null) {
            return entries;
        }
        try {
            openJavaLangToThisModule();
            Field threadLocalsField = Thread.class.getDeclaredField(fieldName);
            threadLocalsField.setAccessible(true);
            Object threadLocalMap = threadLocalsField.get(thread);
            if (threadLocalMap == null) {
                return entries;
            }

            Field tableField = threadLocalMap.getClass().getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(threadLocalMap);
            if (table == null) {
                return entries;
            }

            for (Object entry : table) {
                if (entry == null) {
                    continue;
                }
                entries.add(threadLocalEntryToMap(entry));
            }
        } catch (Throwable e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getClass().getName() + ": " + e.getMessage());
            entries.add(error);
        }
        return entries;
    }

    private static Map<String, Object> threadLocalEntryToMap(Object entry) throws IllegalAccessException, NoSuchFieldException {
        Object key = ((Reference<?>) entry).get();
        Field valueField = entry.getClass().getDeclaredField("value");
        valueField.setAccessible(true);
        Object value = valueField.get(entry);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("keyClass", key == null ? null : key.getClass().getName());
        item.put("keyIdentity", key == null ? null : System.identityHashCode(key));
        item.put("keyString", safeToString(key));
        item.put("valueClass", value == null ? null : value.getClass().getName());
        item.put("valueIdentity", value == null ? null : System.identityHashCode(value));
        item.put("valueString", safeToString(value));
        item.put("stale", key == null);
        return item;
    }

    private static String safeToString(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return String.valueOf(value);
        } catch (Throwable e) {
            return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value))
                    + " toString error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void openJavaLangToThisModule() {
        if (instrumentation == null) {
            return;
        }
        try {
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object javaBaseModule = getModule.invoke(Object.class);
            Object thisModule = getModule.invoke(Global.class);
            Method isOpen = moduleClass.getMethod("isOpen", String.class, moduleClass);
            if ((Boolean) isOpen.invoke(javaBaseModule, "java.lang", thisModule)) {
                return;
            }
            Method redefineModule = Instrumentation.class.getMethod(
                    "redefineModule",
                    moduleClass,
                    Set.class,
                    Map.class,
                    Map.class,
                    Set.class,
                    Map.class
            );
            Map<String, Set<Object>> extraOpens = new HashMap<>();
            extraOpens.put("java.lang", Collections.singleton(thisModule));
            redefineModule.invoke(
                    instrumentation,
                    javaBaseModule,
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    extraOpens,
                    Collections.emptySet(),
                    Collections.emptyMap()
            );
        } catch (ClassNotFoundException ignored) {
            // JDK 8 has no module system.
        } catch (NoSuchMethodException ignored) {
            // Running on a JDK without Instrumentation.redefineModule.
        } catch (Throwable e) {
            Logger.getLogger(Global.class.getName())
                    .log(Level.FINE, "open java.lang module error: {0}: {1}",
                            new Object[]{e.getClass().getSimpleName(), e.getMessage()});
        }
    }

    private static OgnlContext newOgnlContext() {
        return new OgnlContext(
                (s, map) -> {
                    try {
                        return Global.getClassLoader().loadClass(s);
                    } catch (Exception e) {
                        throw new ClassNotFoundException(s);
                    }
                },
                new DefaultTypeConverter(),
                new DefaultMemberAccess(true)
        );
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

    // 0 debug 1 info 2 error
    private static void log(int level, String content) {
        Logger log = Logger.getLogger(Thread.currentThread().getStackTrace()[3].getClassName());
        switch (level) {
            case 0:
                log.log(Level.CONFIG, "[debug]" + content);
                break;
            case 1:
                log.log(Level.INFO, "[info]" + content);
                break;
            case 2:
            default:
                log.log(Level.WARNING, "[error]" + content);
        }
        send(level, content);
    }

    private static void send(int level, String content) {
        LogMessage message = new LogMessage();
        message.setId(RequestUtils.getCurTraceId());
        message.setContent(content);
        message.setLevel(level);
        String json = JSON.toJSONString(message);
        StoredLog storedLog = new StoredLog(message, json);
        synchronized (logHistoryLock) {
            logHistory.addLast(storedLog);
            logHistoryBytes += storedLog.bytes;
            trimLogHistory();
            logHistoryLock.notifyAll();
        }
        for (BlockingQueue<String> subscriber : logSubscribers) {
            subscriber.offer(json);
        }
    }

    private static List<Map<String, Object>> filterLogs(String id, long since, int maxLines) {
        int limit = normalizeMaxLines(maxLines);
        List<Map<String, Object>> logs = new ArrayList<>();
        boolean readAll = "*".equals(id);
        for (StoredLog storedLog : logHistory) {
            LogMessage message = storedLog.message;
            if (since > 0 && message.getTimestamp() < since) {
                continue;
            }
            if (!readAll && !id.equals(message.getId())) {
                continue;
            }
            logs.add(logToMap(message));
            if (logs.size() >= limit) {
                break;
            }
        }
        return logs;
    }

    private static int normalizeMaxLines(int maxLines) {
        if (maxLines <= 0) {
            return LOG_READ_DEFAULT_MAX_LINES;
        }
        return Math.min(maxLines, LOG_READ_MAX_LINES);
    }

    private static Map<String, Object> logToMap(LogMessage message) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("id", message.getId());
        log.put("level", message.getLevel());
        log.put("timestamp", message.getTimestamp());
        log.put("content", message.getContent());
        log.put("type", message.getType());
        return log;
    }

    private static void trimLogHistory() {
        while (logHistory.size() > LOG_HISTORY_MAX_ENTRIES || logHistoryBytes > LOG_HISTORY_MAX_BYTES) {
            StoredLog removed = logHistory.pollFirst();
            if (removed == null) {
                logHistoryBytes = 0;
                break;
            }
            logHistoryBytes -= removed.bytes;
        }
    }

    private static class StoredLog {
        private final LogMessage message;
        private final String json;
        private final int bytes;

        private StoredLog(LogMessage message, String json) {
            this.message = message;
            this.json = json;
            this.bytes = json.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    public static void checkCountAndUnload(String uuid) {
        if (hitCounter.computeIfAbsent(uuid, k -> new AtomicInteger()).incrementAndGet() >= getMaxHit()) {
            info("Watch or trace hit counter exceeded maximum, deleted");
            deleteTransformer(UUID.fromString(uuid));
            hitCounter.remove(uuid);
        }
    }

    public static int getMaxHit() {
        try {
            return Integer.parseInt(System.getProperty("maxHit"));
        } catch (Exception e) {
            return 100;
        }
    }

    public static List<String> readFile(String path) throws IOException {
        return Files.readAllLines(Paths.get(path));
    }

    public synchronized static void fillLoadedClasses() {
        // int count = 0;
        // long start = System.currentTimeMillis();
        for (Class<?> cls : instrumentation.getAllLoadedClasses()) {
            if (cls.getClassLoader() != null) {
                String name = cls.getName();
                allLoadedClasses.computeIfAbsent(name, k -> new HashSet<>())
                        .add(cls);
                // count++;
            }
        }
//        debug("fill loaded classes cost: " + (System.currentTimeMillis() - start) + "ms, class num:" + count);
    }
}
