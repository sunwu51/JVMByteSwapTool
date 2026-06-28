package w.core;

import w.Global;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsonBridgeBundle {
    private static final String BRIDGE_CLASS = "w.core.JsonBridge";
    private static final Map<ClassLoader, BridgeHandle> BRIDGES = new ConcurrentHashMap<>();

    public static String toJson(Object obj) throws Throwable {
        return toJson(obj, -1);
    }

    public static String toJson(Object obj, int maxDepth) throws Throwable {
        ClassLoader parent = chooseParentClassLoader(obj);
        BridgeHandle handle = BRIDGES.get(parent);
        if (handle == null) {
            BridgeHandle created = createBridge(parent);
            BridgeHandle existing = BRIDGES.putIfAbsent(parent, created);
            handle = existing == null ? created : existing;
        }
        try {
            return (String) handle.toJson.invoke(handle.instance, obj, maxDepth);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private static ClassLoader chooseParentClassLoader(Object obj) {
        ClassLoader loader = obj == null ? null : obj.getClass().getClassLoader();
        if (loader != null) {
            return loader;
        }
        return Global.getClassLoader();
    }

    private static BridgeHandle createBridge(ClassLoader parent) throws Exception {
        JsonBridgeClassLoader loader = new JsonBridgeClassLoader(parent);
        Class<?> bridgeClass = loader.loadClass(BRIDGE_CLASS);
        Object instance = bridgeClass.newInstance();
        Method toJson = bridgeClass.getMethod("toJson", Object.class, int.class);
        return new BridgeHandle(instance, toJson);
    }

    private static class BridgeHandle {
        private final Object instance;
        private final Method toJson;

        private BridgeHandle(Object instance, Method toJson) {
            this.instance = instance;
            this.toJson = toJson;
        }
    }

    public static class JsonBridgeClassLoader extends ClassLoader {
        private final ClassLoader agentClassLoader;

        public JsonBridgeClassLoader(ClassLoader parent) {
            super(parent);
            this.agentClassLoader = JsonBridgeBundle.class.getClassLoader();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                if (isBridgeOwnedClass(name)) {
                    try {
                        Class<?> found = findClass(name);
                        if (resolve) {
                            resolveClass(found);
                        }
                        return found;
                    } catch (ClassNotFoundException ignored) {
                        // Fall through to parent for source-tree tests or unexpected packaging.
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = readClassBytes(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        private boolean isBridgeOwnedClass(String name) {
            return name.equals(BRIDGE_CLASS)
                    || name.startsWith("com.alibaba.fastjson2.")
                    || name.startsWith("wshade.com.alibaba.fastjson2.");
        }

        private byte[] readClassBytes(String name) throws ClassNotFoundException {
            String resourceName = name.replace('.', '/') + ".class";
            try (InputStream in = agentClassLoader.getResourceAsStream(resourceName)) {
                if (in == null) {
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
