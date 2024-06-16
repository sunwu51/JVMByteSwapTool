package w;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javassist.*;

import w.core.ExecBundle;
import w.util.SpringUtils;
import w.web.Httpd;
import w.web.Websocketd;

public class App {
    private static final int DEFAULT_HTTP_PORT = 8000;
    private static final int DEFAULT_WEBSOCKET_PORT = 18000;

    public static void agentmain(String arg, Instrumentation instrumentation) throws Exception {
        if (arg != null && arg.length() > 0) {
            String[] items = arg.split("&");
            for (String item : items) {
                String[] kv = item.split("=");
                if (kv.length == 2) {
                    if (System.getProperty(kv[0]) == null) {
                        System.setProperty(kv[0], kv[1]);
                    }
                }
            }
        }
        Global.instrumentation = instrumentation;
        Global.fillLoadedClasses();

        // 1 record the spring boot classloader
        SpringUtils.initFromLoadedClasses();

        // 2 start http and websocket server
        startHttpd(DEFAULT_HTTP_PORT);
        startWebsocketd(DEFAULT_WEBSOCKET_PORT);

        // 3 start a new compiler server
        startCompiler();

        // 4 init execInstance
        initExecInstance();

        // 5 task to clean closed ws and related enhancer
        schedule();
    }
//
//    public static void premain(String arg, Instrumentation instrumentation) throws Exception {
//        agentmain(arg, instrumentation);
//    }

    public static void startCompiler() {
        String os = System.getProperty("os.name").toLowerCase();
        Global.unpackUberJar(Global.getClassLoader());
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";
        String classpath = "";
        Set<String> cps = new HashSet<>();
        for (String cp : Global.getClassPaths()) {

            // Windows os need to delete the first /
            if (os.contains("win") && cp.startsWith("/")) {
                cp = cp.substring(1);
            }

            if (cp.endsWith(".jar")) {
                int pos = cp.lastIndexOf("/") < 0 ? cp.lastIndexOf("\\") : cp.lastIndexOf("/");
                cp = cp.substring(0, pos + 1) + "*";
            }
            if (cp.endsWith(".class")) {
                continue;
            }
            cps.add(cp);
        }
        for (String cp : cps) {
            if (!classpath.isEmpty()) {
                classpath += File.pathSeparator;
            }
            classpath += cp;
        }
        classpath += File.pathSeparator + javaHome + "/../lib/tools.jar" + File.pathSeparator + System.getProperty("java.class.path");
        String className = "w.Compiler";
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(classpath);
        command.add("-Duser.language=en");
        command.add("-Dfile.encoding=UTF-8");
        command.add(className);
        Global.info(String.format("%s -cp %s -Duser.language=en -Dfile.encoding=UTF-8 %s", javaBin, classpath, className));
        try {
            //new jvm process
            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.inheritIO().start();
            Global.info("compiler server started");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Global.info("JVM Shutdown Hook: Shutting down child process.");
                process.destroy();
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Global.error("Shutdown hook interrupted while waiting for child process to end.");
                    Thread.currentThread().interrupt();
                }
            }));
        } catch (Exception e) {
            Global.error("compiler server start error", e);
        }
    }

    private static void startHttpd(int port) throws IOException {
        if (port > 8100) {
            System.err.println("Httpd start failed " + port);
            throw new IOException("Httpd start failed");
        }
        try {
            new Httpd(port).start(5000, false);
            System.out.println("Http server start at port "+ port);
        } catch (IOException e) {
            startHttpd(port + 1);
        }
    }

    private static void startWebsocketd(int port) throws IOException {
        if (port > 18100) {
            System.err.println("Websocketd start failed");
            throw new IOException("Websocketd start failed");
        }
        try {
            new Websocketd(port).start(24 * 60 * 60000, false);
            System.out.println("Websocket server start at port  " +  port);
            Global.wsPort = port;
        } catch (IOException e) {
            startWebsocketd(port + 1);
        }
    }

    private static void initExecInstance() throws CannotCompileException, InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, NotFoundException, IOException {
        ExecBundle.invoke();
    }

    private static void schedule() {
        Executors.newScheduledThreadPool(1)
                .scheduleWithFixedDelay(Global::fillLoadedClasses, 5, 60, TimeUnit.SECONDS);
    }
}
