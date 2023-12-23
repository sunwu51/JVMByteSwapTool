package w;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoWSD;
import javassist.*;
import w.core.ExecBundle;
import w.core.MethodId;
import w.core.Retransformer;
import w.util.SpringUtils;
import w.web.Httpd;
import w.web.Websocketd;

public class App {
    private static final int DEFAULT_HTTP_PORT = 8000;
    private static final int DEFAULT_WEBSOCKET_PORT = 18000;

    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);

    public static void agentmain(String arg, Instrumentation instrumentation) throws Exception {
        Global.instrumentation = instrumentation;


        // 1 record the spring boot classloader
        SpringUtils.initFromLoadedClasses(instrumentation.getAllLoadedClasses());

        // 2 start http and websocket server
        startHttpd(DEFAULT_HTTP_PORT);
        startWebsocketd(DEFAULT_WEBSOCKET_PORT);

        // 3 init execInstance
        initExecInstance();

        // 4 task to clean closed ws and related enhancer
        schedule();
    }

    public static void main(String[] args) throws Exception {

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
        Global.traceIdCtx.set("0000");
        ExecBundle.invoke();
    }

    private static void schedule() {

    }


}
