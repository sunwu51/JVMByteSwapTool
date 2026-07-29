package w.core.diagnostic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.core.TestLoaders;

import java.lang.reflect.Proxy;

class DiagnosticBridgeIsolationTest {
    @Test
    void listenerFailuresNeverEscapeBridge() throws Exception {
        BridgeLoader loader = new BridgeLoader();
        Class<?> listenerType = loader.loadClass("w.bridge.DiagnosticListener");
        Class<?> bridgeType = loader.loadClass("w.bridge.DiagnosticBridge");
        Object throwingListener = Proxy.newProxyInstance(
                loader,
                new Class<?>[]{listenerType},
                (proxy, method, args) -> {
                    throw new AssertionError(method.getName() + " failure");
                });
        bridgeType.getMethod("setListener", listenerType).invoke(null, throwingListener);

        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("info", Object.class)
                .invoke(null, "message"));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("error", Object.class)
                .invoke(null, "message"));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("error", Object.class, Throwable.class)
                .invoke(null, "message", new IllegalStateException("failure")));
        Object token = bridgeType.getMethod("watchEnter", String.class, Object[].class)
                .invoke(null, "broken", new Object[]{new Object[0]});
        Assertions.assertNull(token);
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod(
                        "watchExit", Object.class, long.class, Object.class, Object[].class,
                        Object.class, Throwable.class)
                .invoke(null, new Object(), 0L, null, new Object[0], "result", null));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("outerEnter", String.class)
                .invoke(null, "broken"));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("outerExit", String.class)
                .invoke(null, "broken"));
        Assertions.assertEquals(false, bridgeType.getMethod("outerShouldRecord", String.class)
                .invoke(null, "broken"));
        Assertions.assertNull(bridgeType.getMethod("outerCallEnter", String.class, Object[].class)
                .invoke(null, "broken", new Object[]{new Object[0]}));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod(
                        "outerCallExit", Object.class, int.class, long.class, Object.class,
                        Object[].class, Object.class, Throwable.class)
                .invoke(null, new Object(), 1, 0L, null, new Object[0], "result", null));
        Assertions.assertNull(bridgeType.getMethod("traceEnter", String.class)
                .invoke(null, "broken"));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("traceRecursive", String.class)
                .invoke(null, "broken"));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod(
                        "traceSubCall", String.class, String.class, long.class)
                .invoke(null, "broken", "callee", 1L));
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod("traceExit", Object.class, long.class)
                .invoke(null, new Object(), 0L));
    }

    private static final class BridgeLoader extends ClassLoader {
        private BridgeLoader() {
            super(null);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("w.bridge.")) {
                throw new ClassNotFoundException(name);
            }
            try {
                byte[] bytes = TestLoaders.classBytes(name.replace('.', '/') + ".class");
                return defineClass(name, bytes, 0, bytes.length);
            } catch (Exception e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
