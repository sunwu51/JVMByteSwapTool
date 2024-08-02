package w.core;

import groovy.lang.GroovyClassLoader;
import lombok.Data;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import w.Global;
import w.util.SpringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
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
                Global.info("Groovy Engine Initialization finished");
                if (SpringUtils.isSpring()) {
                    engine.put("ctx", SpringUtils.getSpringBootApplicationContext());
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

        public WGroovyClassLoader(ClassLoader parent) throws Exception {
            super(new URL[] { currentUrl() }, parent);
        }
        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (name.startsWith("org.apache.groovy") || name.startsWith("org.codehaus.groovy") || name.startsWith("groovy") || name.startsWith("w.core.GroovyBundle")) {
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                return findClass(name);
            }
            return super.loadClass(name);
        }
    }
}
