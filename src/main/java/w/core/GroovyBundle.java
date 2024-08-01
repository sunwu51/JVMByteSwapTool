package w.core;

import groovy.lang.GroovyClassLoader;
import lombok.Data;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import w.util.SpringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Frank
 * @date 2024/7/30 22:58
 */
@Data
public class GroovyBundle {
    static GroovyScriptEngineImpl engine;
    static {
        engine = new GroovyScriptEngineImpl(new GroovyClassLoader(w.Global.getClassLoader()));

        if (SpringUtils.isSpring()) {
            engine.put("ctx", SpringUtils.getSpringBootApplicationContext());
        }
    }

    public static Object eval(String script) throws Exception {
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

    public static void main(String[] args) throws Exception {
        System.out.println(GroovyBundle.executeCmd(Arrays.asList("ls")));
    }
}
