package w;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import w.util.SpringUtils;
import w.web.Httpd;

public class App {
    private static final int DEFAULT_HTTP_PORT = 8000;
    private static final String STARTED_PROPERTY = "swapper.agent.started";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService scheduler;
    private static volatile Httpd httpd;

    public static void premain(String arg, Instrumentation instrumentation) throws Exception {
        start("premain", arg, instrumentation);
    }

    public static void agentmain(String arg, Instrumentation instrumentation) throws Exception {
        start("agentmain", arg, instrumentation);
    }

    private static void start(String mode, String arg, Instrumentation instrumentation) throws Exception {
        if (Boolean.parseBoolean(System.getProperty(STARTED_PROPERTY)) || !STARTED.compareAndSet(false, true)) {
            System.out.println("JVMByteSwapTool already started, skip " + mode);
            return;
        }
        try {
            System.setProperty(STARTED_PROPERTY, "true");
            applyArgs(arg);
            Global.instrumentation = instrumentation;
            Global.fillLoadedClasses();

            // In premain, Spring is usually not ready yet. Try once now, then keep probing in background.
            tryInitSpringContext();

            startHttpd();
            schedule();
        } catch (Throwable e) {
            STARTED.set(false);
            System.clearProperty(STARTED_PROPERTY);
            throw e;
        }
    }

    private static void applyArgs(String arg) {
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
    }

    private static void startHttpd() throws Exception {
        int port = DEFAULT_HTTP_PORT;
        if (System.getProperty("http_port") != null) {
            port = Integer.parseInt(System.getProperty("http_port"));
        }
        httpd = new Httpd(port);
        httpd.start();
        System.out.println("Http server start at port " + port);
    }

    private static void schedule() {
        scheduler = Executors.newScheduledThreadPool(1, daemonThreadFactory());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                Global.fillLoadedClasses();
                tryInitSpringContext();
            } catch (Throwable e) {
                Global.error("background refresh error:", e);
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "JVMByteSwapTool-refresh");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static boolean tryInitSpringContext() {
        try {
            if (SpringUtils.initFromLoadedClasses()) {
                return true;
            }
        } catch (Throwable e) {
            Global.debug("spring application context not ready: " + e.getMessage());
        }
        return false;
    }
}
