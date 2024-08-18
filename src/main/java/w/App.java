package w;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
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

        String workingDir = System.getProperty("user.dir");
        String configFilePath = workingDir + File.separator + "swapConfig.properties";
        File configFile = new File(configFilePath);
        if (configFile.exists()) {
            System.out.println("Reading configuration file");
            Properties properties = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                // Read configuration, customize ports
                Integer customHttpPort = Integer.parseInt(properties.getProperty("CUSTOM_HTTP_PORT",Integer.valueOf(DEFAULT_HTTP_PORT).toString()));
                Integer customWebsocketPort = Integer.parseInt(properties.getProperty("CUSTOM_WEBSOCKET_PORT",Integer.valueOf(DEFAULT_WEBSOCKET_PORT).toString()));

                startHttpd(customHttpPort);
                startWebsocketd(customWebsocketPort);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }else {
            startHttpd(DEFAULT_HTTP_PORT);
            startWebsocketd(DEFAULT_WEBSOCKET_PORT);
        }


        // 3 init execInstance
        initExecInstance();

        // 4 task to clean closed ws and related enhancer
        schedule();
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
