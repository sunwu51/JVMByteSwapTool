package w.core.diagnostic;

import w.bridge.DiagnosticBridge;

import java.io.IOException;

public final class DiagnosticRuntime {
    private static final AgentDiagnosticListener LISTENER = new AgentDiagnosticListener();

    private DiagnosticRuntime() {
    }

    public static synchronized void initialize() throws IOException {
        Class<?> bootstrapBridge;
        try {
            bootstrapBridge = Class.forName("w.bridge.DiagnosticBridge", false, null);
        } catch (ClassNotFoundException e) {
            throw new IOException("bootstrap diagnostic bridge is unavailable", e);
        }
        if (DiagnosticBridge.class != bootstrapBridge) {
            throw new IOException("agent resolves a different DiagnosticBridge class");
        }
        DiagnosticBridge.setListener(LISTENER);
    }

    public static void register(WatchConfig config) {
        LISTENER.register(config);
    }

    public static void register(OuterWatchConfig config) {
        LISTENER.register(config);
    }

    public static void register(TraceConfig config) {
        LISTENER.register(config);
    }

    public static void unregister(String id) {
        LISTENER.unregister(id);
    }

    public static boolean isRegistered(String id) {
        return LISTENER.contains(id);
    }
}
