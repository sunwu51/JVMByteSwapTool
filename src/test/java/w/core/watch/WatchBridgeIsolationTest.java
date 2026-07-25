package w.core.watch;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.core.TestLoaders;

import java.lang.reflect.Proxy;

class WatchBridgeIsolationTest {
    @Test
    void listenerFailuresNeverEscapeBridge() throws Exception {
        BridgeLoader loader = new BridgeLoader();
        Class<?> listenerType = loader.loadClass("w.bridge.WatchListener");
        Class<?> bridgeType = loader.loadClass("w.bridge.WatchBridge");
        Object throwingListener = Proxy.newProxyInstance(
                loader,
                new Class<?>[]{listenerType},
                (proxy, method, args) -> {
                    throw new AssertionError(method.getName() + " failure");
                });
        bridgeType.getMethod("setListener", listenerType).invoke(null, throwingListener);

        Object token = bridgeType.getMethod("enter", String.class).invoke(null, "broken");
        Assertions.assertNull(token);
        Assertions.assertDoesNotThrow(() -> bridgeType.getMethod(
                        "exit", Object.class, long.class, Object.class, Object[].class,
                        Object.class, Throwable.class)
                .invoke(null, new Object(), 0L, null, new Object[0], "result", null));
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
