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
        startHttpd();
        startWebsocketd();

        // 3 init execInstance
        initExecInstance();

        // 4 task to clean closed ws and related enhancer
        schedule();
    }

    private static void startHttpd() throws IOException {
        int port = DEFAULT_HTTP_PORT;
        if (System.getProperty("http_port") != null) {
            port = Integer.parseInt(System.getProperty("http_port"));
        }
        new Httpd(port).start(5000, false);
        System.out.println("Http server start at port "+ port);
    }

    private static void startWebsocketd() throws IOException {
        int port = DEFAULT_WEBSOCKET_PORT;
        if (System.getProperty("ws_port") != null) {
            port = Integer.parseInt(System.getProperty("ws_port"));
        }
        new Websocketd(port).start(24 * 60 * 60000, false);
        System.out.println("Websocket server start at port  " +  port);
        Global.wsPort = port;
    }

    private static void initExecInstance() throws CannotCompileException, InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, NotFoundException, IOException {
        ExecBundle.invoke();
    }

    private static void schedule() {
        Executors.newScheduledThreadPool(1)
                .scheduleWithFixedDelay(Global::fillLoadedClasses, 5, 60, TimeUnit.SECONDS);
    }
}
