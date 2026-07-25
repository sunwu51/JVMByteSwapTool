package w.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Extracts only the JDK-only bridge API into the bootstrap class path.
 */
public final class BootstrapBridgeInstaller {
    private static final String[] BRIDGE_CLASSES = {
            "w/bridge/WatchBridge.class",
            "w/bridge/WatchListener.class"
    };
    private static boolean installed;
    private static File bridgeJar;

    private BootstrapBridgeInstaller() {
    }

    public static synchronized void install(Instrumentation instrumentation) throws IOException {
        if (installed) {
            return;
        }
        if (instrumentation == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        File jar = File.createTempFile("swapper-bootstrap-bridge-", ".jar");
        jar.deleteOnExit();
        try {
            writeBridgeJar(jar);
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            Class<?> bridge = Class.forName("w.bridge.WatchBridge", true, null);
            if (bridge.getClassLoader() != null) {
                throw new IllegalStateException("WatchBridge was not loaded by bootstrap");
            }
            bridgeJar = jar;
            installed = true;
        } catch (IOException | RuntimeException | ClassNotFoundException e) {
            if (!jar.delete()) {
                jar.deleteOnExit();
            }
            throw new IOException("install bootstrap watch bridge failed", e);
        }
    }

    public static synchronized boolean isInstalled() {
        return installed;
    }

    static synchronized File getBridgeJar() {
        return bridgeJar;
    }

    private static void writeBridgeJar(File jar) throws IOException {
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jar))) {
            ClassLoader sourceLoader = BootstrapBridgeInstaller.class.getClassLoader();
            for (String resource : BRIDGE_CLASSES) {
                try (InputStream input = sourceLoader.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IOException("missing bridge resource: " + resource);
                    }
                    output.putNextEntry(new JarEntry(resource));
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    output.closeEntry();
                }
            }
        }
    }
}
