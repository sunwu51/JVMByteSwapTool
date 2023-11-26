package com.example.vmproxy;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

import com.example.vmproxy.web.Httpd;
import com.example.vmproxy.web.Websocketd;
import lombok.extern.slf4j.Slf4j;
// NOTE: If you're using NanoHTTPD >= 3.0.0 the namespace is different,
//       instead of the above import use the following:
// import org.nanohttpd.NanoHTTPD;

@Slf4j
public class App {
    private static final int HTTP_PORT = 18000;
    private static final int WEBSOCKET_PORT = 18001;

    public static void agentmain(String arg, Instrumentation instrumentation) throws IOException, InterruptedException {
        Global.instrumentation = instrumentation;
        main(new String[0]);

    }
    public static void main(String[] args) throws IOException, InterruptedException {
        int port1 = HTTP_PORT, port2 = WEBSOCKET_PORT;

        new Websocketd(port2).start(10000, false);
        log.info("http serve at port {}", HTTP_PORT);
        log.info("ws serve at port {}", WEBSOCKET_PORT);
    }

}
