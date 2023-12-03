package w;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoWSD;
import javassist.ClassPool;
import w.core.MethodId;
import w.core.Retransformer;
import w.core.Swapper;
import w.web.Httpd;
import w.web.Websocketd;
import lombok.extern.slf4j.Slf4j;
import w.web.util.HttpUtil;

@Slf4j
public class App {
    private static final int DEFAULT_HTTP_PORT = 8000;
    private static final int DEFAULT_WEBSOCKET_PORT = 18000;

    private static final String PORT_KEY = "port";

    private static int springWebPort = -1;

    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);

    public static void agentmain(String arg, Instrumentation instrumentation) throws Exception {
        Thread.currentThread().setContextClassLoader(App.class.getClassLoader());
        Global.classPool.appendSystemPath();
        Global.instrumentation = instrumentation;
        Map<String, String> params = parseQueryString(arg);
        springWebPort = Integer.parseInt(params.get(PORT_KEY));
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        startHttpd(DEFAULT_HTTP_PORT);
        startWebsocketd(DEFAULT_WEBSOCKET_PORT);
        ingestSpringApplicationContext(springWebPort);
        schedule();
    }

    private static void startHttpd(int port) throws IOException {
        if (port > 8100) {
            log.error("Httpd start failed " + port);
            throw new IOException("Httpd start failed");
        }
        try {
            new Httpd(port).start(5000, false);
            log.info("Http server start at port {}", port);
        } catch (IOException e) {
            startHttpd(port + 1);
        }
    }

    private static void startWebsocketd(int port) throws IOException {
        if (port > 18100) {
            log.error("Websocketd start failed");
            throw new IOException("Websocketd start failed");
        }
        try {
            new Websocketd(port).start(30000, false);
            log.info("Websocket server start at port {}", port);
            Global.wsPort = port;
        } catch (IOException e) {
            startWebsocketd(port + 1);
        }
    }

    /**
     * Ingest Spring ApplicationContext by visit the web endpoint
     * @param port
     */

    private static void ingestSpringApplicationContext(int port) {
        try {
            Swapper.getInstance().getSpringCtx();
            HttpUtil.doGet("http://127.0.0.1:" + port);
            assert Global.springApplicationContext != null;
        } catch (Exception e) {
            log.warn("Ingest spring context error", e);
        }
    }

    private static Map<String, String> parseQueryString(String queryString) {
        Map<String, String> result = new HashMap<>();
        if (queryString == null) return result;

        for (String kv : queryString.split("&")) {
            String[] arr = kv.split("=");
            assert arr.length == 2;
            result.put(arr[0], arr[1]);
        }
        return result;
    }

    private static void schedule() {
        pool.scheduleAtFixedRate(() -> {
            // delete the closed ws and ctx
            Set<String> removeTraces = new HashSet<>();
            for (Map.Entry<String, NanoWSD.WebSocket> kv : Global.socketMap.entrySet()) {
                NanoWSD.WebSocket ws = kv.getValue();
                if (ws == null || !ws.isOpen()) {
                    removeTraces.add(kv.getKey());
                }
            }
            for (String removeTrace : removeTraces) {
                Global.socketMap.remove(removeTrace);
                Map<MethodId, Retransformer> map = Global.traceId2MethodId2Trans.remove(removeTrace);
                if (map != null) {
                    map.forEach(((methodId, retransformer) -> {
                        Global.instrumentation.removeTransformer(retransformer.getClassFileTransformer());
                        try {
                            Global.instrumentation.retransformClasses(Class.forName(methodId.getClassName()));
                        } catch (UnmodifiableClassException e) {
                        } catch (ClassNotFoundException e) {
                        }
                    }));
                }
            }

        }, 0, 3, TimeUnit.SECONDS);
    }
}
