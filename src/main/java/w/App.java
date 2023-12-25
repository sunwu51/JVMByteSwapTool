package w;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import javassist.*;

import w.core.ExecBundle;
import w.util.SpringUtils;
import w.web.Httpd;
import w.web.Websocketd;

public class App {
    private static final int DEFAULT_HTTP_PORT = 8000;
    private static final int DEFAULT_WEBSOCKET_PORT = 18000;

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
//
//    public static void premain(String arg, Instrumentation instrumentation) throws Exception {
//        agentmain(arg, instrumentation);
//    }

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

    }


}
