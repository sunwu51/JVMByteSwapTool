package w;

import java.lang.instrument.Instrumentation;

import w.core.Retransformer;
import w.web.Websocketd;
import lombok.extern.slf4j.Slf4j;
import w.web.util.HttpUtil;
// NOTE: If you're using NanoHTTPD >= 3.0.0 the namespace is different,
//       instead of the above import use the following:
// import org.nanohttpd.NanoHTTPD;

@Slf4j
public class App {
    private static final int HTTP_PORT = 18000;
    private static final int WEBSOCKET_PORT = 18001;

    public static void agentmain(String arg, Instrumentation instrumentation) throws Exception {
        Global.instrumentation = instrumentation;
        for (Class aClass : instrumentation.getAllLoadedClasses()) {
            System.out.println(aClass);
        }
        main(new String[] {arg});

    }
    public static void main(String[] args) throws Exception {
        new Websocketd(WEBSOCKET_PORT).start(10000, false);
        log.info("ws serve at port {}", WEBSOCKET_PORT);
        try {
            String springPort = args.length >= 1 ? args[0] : "8080";
            Retransformer.getInstance().getSpringCtx();
            HttpUtil.doGet("http://127.0.0.1:" + springPort);
        } catch (Exception e) {
            log.info("spring web獲取失敗");
        }
    }

}
