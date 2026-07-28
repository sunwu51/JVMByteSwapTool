package w.core;

import lombok.Data;
import w.Global;
import w.util.JarInJarClassLoader;
import w.util.SpringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static w.Attach.currentUrl;

/**
 * @author Frank
 * @date 2024/7/30 22:58
 */
@Data
public class GroovyBundle {
    private static final Map<ClassLoader, EngineContext> ENGINES = new IdentityHashMap<>();
    private static JarInJarClassLoader groovyLibraryClassLoader;

    public static Object eval(String script) throws Exception {
        return eval(script, Global.getClassLoader());
    }

    public static Object eval(String script, ClassLoader targetClassLoader) throws Exception {
        if (script.startsWith("!")) {
            return executeCmd(Arrays.asList(script.substring(1).split(" ")));
        }
        EngineContext context = engineContext(targetClassLoader);
        synchronized (context) {
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(context.classLoader);
                return context.evalMethod.invoke(context.engine, script);
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }

    private static EngineContext engineContext(ClassLoader targetClassLoader) throws Exception {
        synchronized (ENGINES) {
            EngineContext existing = ENGINES.get(targetClassLoader);
            if (existing != null) {
                return existing;
            }
            EngineContext created = createEngineContext(targetClassLoader);
            ENGINES.put(targetClassLoader, created);
            return created;
        }
    }

    private static EngineContext createEngineContext(ClassLoader targetClassLoader) throws Exception {
        WGroovyClassLoader classLoader = new WGroovyClassLoader(
                groovyLibraryClassLoader(), targetClassLoader);
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(classLoader);
            Class<?> engineClass = classLoader.loadClass("org.codehaus.groovy.jsr223.GroovyScriptEngineImpl");
            Class<?> groovyClassLoaderClass = classLoader.loadClass("groovy.lang.GroovyClassLoader");
            Object engine = engineClass.getConstructor(groovyClassLoaderClass)
                    .newInstance(groovyClassLoaderClass.newInstance());
            engineClass.getMethod("put", String.class, Object.class)
                    .invoke(engine, "ctx", SpringUtils.getSpringBootApplicationContext());
            return new EngineContext(classLoader, engine,
                    engineClass.getMethod("eval", String.class));
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static synchronized JarInJarClassLoader groovyLibraryClassLoader() throws Exception {
        if (groovyLibraryClassLoader == null) {
            groovyLibraryClassLoader = new JarInJarClassLoader(
                    currentUrl(), "W-INF/lib", ClassLoader.getSystemClassLoader().getParent());
        }
        return groovyLibraryClassLoader;
    }

    static int engineCount() {
        synchronized (ENGINES) {
            return ENGINES.size();
        }
    }

    public static void clearEngines() {
        synchronized (ENGINES) {
            for (EngineContext context : ENGINES.values()) {
                synchronized (context) {
                    try {
                        context.classLoader.close();
                    } catch (IOException e) {
                        Global.error("close Groovy ClassLoader error", e);
                    }
                }
            }
            ENGINES.clear();
        }
    }

    private static String executeCmd(List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        List<String> command = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            command.add("cmd.exe");
            command.add("/c");
        } else {
            command.add("sh");
            command.add("-c");
        }
        command.add(String.join(" ", args));
        builder.command(command);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        new Thread(() -> {
            BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            try {
                while ((line = stdout.readLine()) != null) {
                    output.append(line).append('\n');
                }
                while ((line = stderr.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
        return process.waitFor(10, TimeUnit.SECONDS) ? output.toString() : "timeout";
    }

    private static final class EngineContext {
        private final WGroovyClassLoader classLoader;
        private final Object engine;
        private final Method evalMethod;

        private EngineContext(WGroovyClassLoader classLoader, Object engine, Method evalMethod) {
            this.classLoader = classLoader;
            this.engine = engine;
            this.evalMethod = evalMethod;
        }
    }

    public static class WGroovyClassLoader extends URLClassLoader {
        private final ClassLoader delegate;
        private final ClassLoader agentClassLoader;

        public WGroovyClassLoader(ClassLoader parent, ClassLoader delegate) throws Exception {
            super(new URL[]{currentUrl()}, parent);
            this.delegate = delegate;
            this.agentClassLoader = GroovyBundle.class.getClassLoader();
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (isAgentApiClass(name)) {
                return agentClassLoader.loadClass(name);
            }
            try {
                return getParent().loadClass(name);
            } catch (ClassNotFoundException e) {
                if (delegate == null) {
                    return Class.forName(name, false, null);
                }
                return delegate.loadClass(name);
            }
        }

        private boolean isAgentApiClass(String name) {
            return name.equals("w.Global")
                    || name.startsWith("w.util.")
                    || name.startsWith("w.core.")
                    || name.startsWith("w.web.message.");
        }
    }
}
