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
import w.core.Swapper;
import w.web.Httpd;
import w.web.Websocketd;
import w.util.HttpUtil;

public class App {
    private static final int DEFAULT_HTTP_PORT = 8000;
    private static final int DEFAULT_WEBSOCKET_PORT = 18000;

    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);

    public static void agentmain(String arg, Instrumentation instrumentation) throws Exception {
        Global.instrumentation = instrumentation;


        // 1 record the spring boot classloader
        for (Class c : Global.instrumentation.getAllLoadedClasses()) {
            // if it is a spring boot fat jar, the class loader will be LaunchedURLClassLoader, for spring boot >1 and <3
            if (c.getClassLoader() == null) continue;
            if (c.getName().equals("org.springframework.context.ApplicationContext")) {
                Object[] instances = Global.getInstances(c);
                assert instances.length == 1;
                Global.springApplicationContext = instances[0];
                ClassLoader cl = c.getClassLoader();
                System.out.println("find springboot application context is loaded by " + cl);
                Global.springBootCl = c.getClassLoader();
                break;
            }
        }

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
        CtClass ctClass = Global.classPool.makeClass("w.Exec");
        ctClass.defrost();
        CtMethod ctMethod = CtMethod.make("public void exec() {}", ctClass);
        ctClass.addMethod(ctMethod);
        Class c = ctClass.toClass(Global.getClassLoader());
        ExecBundle bundle = new ExecBundle();
        bundle.setInst(c.newInstance());
        bundle.setCtClass(ctClass);
        bundle.setCtMethod(ctMethod);
        Global.execBundle = bundle;
        bundle.invoke();
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
                            Global.instrumentation.retransformClasses(Global.getClassLoader().loadClass(methodId.getClassName()));
                        } catch (UnmodifiableClassException e) {
                        } catch (ClassNotFoundException e) {
                        }
                    }));
                }
            }

        }, 0, 3, TimeUnit.SECONDS);
    }


}
