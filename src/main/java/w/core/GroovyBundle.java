package w.core;

import groovy.lang.GroovyClassLoader;
import lombok.Data;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import sun.misc.CompoundEnumeration;
import w.Global;
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
    static WGroovyClassLoader cl;
    static GroovyScriptEngineImpl engine;
    static {
        if (GroovyBundle.class.getClassLoader().toString().startsWith(WGroovyClassLoader.class.getName())) {
            try {
                engine = new GroovyScriptEngineImpl(new GroovyClassLoader());
                Global.info("Groovy Engine Initialization finished ctx loader is " + Thread.currentThread().getContextClassLoader());
                if (SpringUtils.isSpring()) {
                    engine.put("ctx", SpringUtils.getSpringBootApplicationContext());
                }

                Enumeration e = Thread.currentThread().getContextClassLoader().getResources("META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule");
                while (e.hasMoreElements()) {
                    System.out.println( e.nextElement());
                }
            } catch (Exception e) {
                Global.error("Could not load Groovy Engine", e);
            }
        } else {
            try {
                cl = new WGroovyClassLoader(Global.getClassLoader());
            } catch (Exception e) {
                Global.error("Could not init Groovy Classloader", e);
            }
        }
    }

    public static Object eval(String script) throws Exception {
        if (cl != null) {
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> bundle = cl.loadClass(GroovyBundle.class.getName());
            return bundle.getDeclaredMethod("eval", String.class).invoke(null, script);
        }

        if (script.startsWith("!")) {
            return executeCmd(Arrays.asList(script.substring(1).split(" ")));
        } else {
            return engine.eval(script);
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
        public WGroovyClassLoader(ClassLoader delegate) throws Exception {
            super(new URL[] { currentUrl() }, String.class.getClassLoader());
            this.delegate = delegate;
        }
        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("w.") && !name.equals(GroovyBundle.class.getName())) {
                return delegate.loadClass(name);
            }
            try {
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                c = findClass(name);
                return c;
            } catch (ClassNotFoundException e) {
                return delegate.loadClass(name);
            }
        }

    }
}
