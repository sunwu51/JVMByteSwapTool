package w.core.debug;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures class bytes, preferring resource and initial-load sources before a
 * read-only retransformation fallback registered ahead of Swapper transformers.
 */
public final class OriginalBytecodeObserver implements ClassFileTransformer {
    private static final OriginalBytecodeObserver INSTANCE = new OriginalBytecodeObserver();
    private static final ConcurrentHashMap<ClassKey, byte[]> INITIAL_BYTES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ClassKey, byte[]> RECOVERED_BYTES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, AtomicReference<byte[]>>
            RECOVERY_REQUESTS = new ConcurrentHashMap<>();
    private static final ClassFileTransformer RECOVERY_OBSERVER =
            (loader, className, classBeingRedefined, protectionDomain, classfileBuffer) -> {
                if (classBeingRedefined == null || classfileBuffer == null) {
                    return null;
                }
                AtomicReference<byte[]> request = RECOVERY_REQUESTS.get(classBeingRedefined);
                if (request != null) {
                    request.compareAndSet(null, classfileBuffer.clone());
                }
                return null;
            };
    private static volatile Instrumentation instrumentation;

    private OriginalBytecodeObserver() {
    }

    public static synchronized void initialize(Instrumentation value) {
        if (instrumentation != null) {
            return;
        }
        if (value == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        // Observe initial class loading only. A retransformation-capable observer
        // would receive input after transformers installed before Swapper.
        value.addTransformer(INSTANCE);
        if (value.isRetransformClassesSupported()) {
            // Registered before Swapper's mutating transformers. Used only as a
            // last resort for preloaded classes whose loader exposes no resource.
            value.addTransformer(RECOVERY_OBSERVER, true);
        }
        instrumentation = value;
    }

    static byte[] capture(Class<?> target) throws Exception {
        Instrumentation current = instrumentation;
        if (current == null) {
            throw new IllegalStateException("OriginalBytecodeObserver is not initialized");
        }
        byte[] resource = readClassResource(target);
        if (resource.length > 0) {
            return resource;
        }
        ClassKey key = new ClassKey(target.getClassLoader(), target.getName());
        byte[] original = INITIAL_BYTES.get(key);
        if (original != null) {
            return original.clone();
        }
        byte[] recovered = RECOVERED_BYTES.get(key);
        if (recovered != null) {
            return recovered.clone();
        }
        synchronized (target) {
            recovered = RECOVERED_BYTES.get(key);
            if (recovered == null) {
                recovered = captureRetransformBaseline(current, target);
                RECOVERED_BYTES.put(key, recovered);
            }
            return recovered.clone();
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (classBeingRedefined == null && className != null && classfileBuffer != null) {
            INITIAL_BYTES.putIfAbsent(new ClassKey(loader, className.replace('/', '.')),
                    classfileBuffer.clone());
        }
        return null;
    }

    private static byte[] readClassResource(Class<?> target) {
        String resourceName = target.getName().replace('.', '/') + ".class";
        try (InputStream input = target.getResourceAsStream("/" + resourceName)) {
            if (input == null) {
                return new byte[0];
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    private static byte[] captureRetransformBaseline(
            Instrumentation current, Class<?> target) throws Exception {
        if (!current.isRetransformClassesSupported()
                || !current.isModifiableClass(target)) {
            throw unavailable(target, null);
        }
        AtomicReference<byte[]> captured = new AtomicReference<>();
        RECOVERY_REQUESTS.put(target, captured);
        try {
            current.retransformClasses(target);
        } catch (Exception error) {
            throw unavailable(target, error);
        } finally {
            RECOVERY_REQUESTS.remove(target, captured);
        }
        byte[] bytes = captured.get();
        if (bytes == null || bytes.length == 0) {
            throw unavailable(target, null);
        }
        return bytes;
    }

    private static IllegalStateException unavailable(Class<?> target, Throwable cause) {
        String message = "Original bytecode unavailable: " + target.getName();
        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }

    private static final class ClassKey {
        private final ClassLoader loader;
        private final String name;

        private ClassKey(ClassLoader loader, String name) {
            this.loader = loader;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ClassKey)) {
                return false;
            }
            ClassKey key = (ClassKey) other;
            return loader == key.loader && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(loader) + name.hashCode();
        }
    }
}
