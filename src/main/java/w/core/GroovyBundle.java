package w.core;

import groovy.lang.GroovyClassLoader;
import lombok.Data;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import w.Global;
import w.util.JarInJarClassLoader;
import w.util.SpringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static w.Attach.currentUrl;

/**
 * @author Frank
 * @date 2024/7/30 22:58
 */
@Data
public class GroovyBundle {
    static ClassLoader cl;

    static Object engineObj;

    static {
        try {
            JarInJarClassLoader jarInJarClassLoader =
                    new JarInJarClassLoader(currentUrl(), "W-INF/lib", ClassLoader.getSystemClassLoader().getParent());
            cl = new WGroovyClassLoader(jarInJarClassLoader, Global.getClassLoader());
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> engineClass = cl.loadClass("org.codehaus.groovy.jsr223.GroovyScriptEngineImpl");
            Class<?> gclClass = cl.loadClass("groovy.lang.GroovyClassLoader");
            engineObj = engineClass.getConstructor(gclClass).newInstance(gclClass.newInstance());
            engineClass.getMethod("put", String.class, Object.class).invoke(engineObj, "ctx", SpringUtils.getSpringBootApplicationContext());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object eval(String script) throws Exception {
        if (script.startsWith("!")) {
            return executeCmd(Arrays.asList(script.substring(1).split(" ")));
        } else {
            return engineObj.getClass().getMethod("eval", String.class).invoke(engineObj, script);
        }
    }

    private static String executeCmd(List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        List<String> _args = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            _args.add("cmd.exe");
            _args.add("/c");
        } else {
            _args.add("sh");
            _args.add("-c");
        }
        _args.add(String.join(" ", args));
        builder.command(_args);
        Process process = builder.start();

        StringBuilder sb = new StringBuilder();
        new Thread(() -> {
            BufferedReader reader1 = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader reader2 = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line1, line2;
            while (true) {
                try {
                    if ((line1 = reader1.readLine()) == null) break;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                sb.append(line1).append('\n');
            }
            while (true) {
                try {
                    if ((line2 = reader2.readLine()) == null) break;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                sb.append(line2).append('\n');
            }
        }).start();
        return process.waitFor(10, TimeUnit.SECONDS) ? sb.toString() : "timeout";
    }

    public static class WGroovyClassLoader extends URLClassLoader {
        private final ClassLoader delegate;
        public WGroovyClassLoader(ClassLoader parent, ClassLoader delegate) throws Exception {
            super(new URL[] { currentUrl() }, parent);
            this.delegate = Global.getClassLoader();
        }
        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // For entrypoint class, must load it by self
            if (name.equals(GroovyBundle.class.getName())) {
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                return findClass(name);
            }
            try {
                // For groovy, need to load it by parent(jarInJarClassLoader)
                return getParent().loadClass(name);
            } catch (ClassNotFoundException e) {
                // Else load it by delegate(Global.getClassLoader())
                return delegate.loadClass(name);
            }
        }
    }
}
