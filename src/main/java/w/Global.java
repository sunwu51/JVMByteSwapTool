package w;

import com.fasterxml.jackson.core.JsonProcessingException;
import javassist.ClassPool;
import ognl.*;
import w.core.model.BaseClassTransformer;
import w.util.NativeUtils;
import w.util.PrintUtils;
import w.util.RequestUtils;
import w.util.SpringUtils;
import w.web.message.LogMessage;
import fi.iki.elonen.NanoWSD;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
     * Transformer hitCounter, for watch trace the transformer effects 50 times at most by default.
     * Change the default value by environment variable $HIT_COUNT
     */
    public static Map<String, AtomicInteger> hitCounter = new ConcurrentHashMap<>();

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
        try {
            return PrintUtils.getObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            Global.error("re transform error:", e);
            return "toJson error";
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
        String className = c.getName();
        String classLoader = c.getClassLoader().toString();
        activeTransformers.computeIfAbsent(className, k->new HashMap<>()).computeIfAbsent(classLoader, k->new ArrayList<>()).add(transformer);
        instrumentation.retransformClasses(c);
    }


    /**
     * Remove transformer By uuid
     * @param uuid
     */
    public static synchronized void deleteTransformer(UUID uuid) {
        debug("Deleting transformer " + uuid);
        Set<String> delClass = new HashSet<>();
        transformers.removeIf(it -> {
            if (it.getUuid().equals(uuid)) {
                it.setStatus(-1);
                it.clear();
                instrumentation.removeTransformer(it);
                for (Class<?> aClass : allLoadedClasses.getOrDefault(it.getClassName(), new HashSet<>())) {
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
        debug("Deleted transformer " + uuid);
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

    private static synchronized void send(int level, String content) {
        Iterator<NanoWSD.WebSocket> it = webSockets.iterator();
        while (it.hasNext()) {
            NanoWSD.WebSocket ws = it.next();
            if (ws != null && ws.isOpen()) {
                try {
                    LogMessage message = new LogMessage();
                    message.setId(RequestUtils.getCurTraceId());
                    message.setContent(content);
                    message.setLevel(level);
                    ws.send(toJson(message));
                } catch (IOException e) {
                    System.err.println("send message error" + e);
                }
            } else {
                it.remove();
            }
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
        int count = 0;
        long start = System.currentTimeMillis();
        for (Class cls : instrumentation.getAllLoadedClasses()) {
            if (cls.getClassLoader() != null) {
                String name = cls.getName();
                allLoadedClasses.computeIfAbsent(name, k -> new HashSet<>())
                        .add(cls);
                count ++;

            }
        }
//        debug("fill loaded classes cost: " + (System.currentTimeMillis() - start) + "ms, class num:" + count);
    }

    public static Set<String> getClassPaths() {
        Set<String> result = new HashSet<>();
        result.addAll(classPaths);
        CodeSource codeSource  = Global.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            String jarPath = codeSource.getLocation().getPath();
            result.add(jarPath);
        }
        return result;
    }


    public static void unpackUberJar(ClassLoader classLoader) {
        try {
            Map<String, Set<String>> innerInfo = new HashMap<>();
            Enumeration<URL> urls = classLoader.getResources("");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                // spring uber jar BOOT-INF/classes and BOOT-INF/lib/dependency.jar!/ ends with !/
                String[] parts = url.toString().split("!/");
                if (parts.length == 2 && url.toString().endsWith("!/")) {
                    String parentJar = parts[0].substring(parts[0].lastIndexOf(":") + 1);
                    String innerPath = parts[1];
                    innerInfo.computeIfAbsent(parentJar, k->new HashSet<>()).add(innerPath);
                }
            }
            String tempDir = System.getProperty("java.io.tmpdir");
            String targetDir = tempDir + "/" + System.nanoTime() + "_classpath";
            innerInfo.forEach((jar, inners) -> {
                try {
                    classPaths.addAll(extractBootInfClasses(jar, targetDir, inners));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            classPaths.add(new File(targetDir).getAbsolutePath());
            Global.tempCompileClassPath = targetDir;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    deleteRecursively(new File(targetDir));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));

        } catch (Exception e) {
            e.printStackTrace();
            Global.error("unpack uber jar failed, execute can only use javassist mode!");
        }

    }

    private static Set<String> extractBootInfClasses(String jarFilePath, String destDirPath, Set<String> inners) throws IOException {
        Set<String> result = new HashSet<>();
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                String innerName = null;
                for (String inner : inners) {
                    if (entryName.startsWith(inner)) {
                        innerName = inner;
                        break;
                    }
                }
                if (innerName != null) {
                    String prefix = "";
                    if (innerName.endsWith(".jar")) {
                        prefix = innerName.substring(0, innerName.lastIndexOf("/"));
                    } else {
                        prefix = innerName.endsWith("/") ? innerName : innerName + "/";
                    }
                    // directory for class files
                    if (entry.isDirectory()) {
                        continue;
                    }

                    Path targetPath = Paths.get(destDirPath + "/" + entryName.substring(prefix.length()));

                    if (Files.notExists(targetPath.getParent())) {
                        Files.createDirectories(targetPath.getParent());
                    }

                    try (InputStream is = jarFile.getInputStream(entry);
                         OutputStream os = Files.newOutputStream(targetPath)) {
                        copyStream(is, os);
                        result.add(targetPath.toFile().getAbsolutePath());
                    }
                }
            }
        }
        return result;
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + file);
        }
    }
}
