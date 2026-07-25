package w.core.watch;

import w.bridge.WatchBridge;

public final class WatchRuntime {
    private static final AgentWatchListener LISTENER = new AgentWatchListener();
    private static boolean initialized;

    private WatchRuntime() {
    }

    public static synchronized void initialize() {
        if (!initialized) {
            initialized = true;
        }
        WatchBridge.setListener(LISTENER);
    }

    public static void register(WatchConfig config) {
        initialize();
        LISTENER.register(config);
    }

    public static void unregister(String id) {
        LISTENER.unregister(id);
    }

    public static boolean isRegistered(String id) {
        return LISTENER.contains(id);
    }

    static AgentWatchListener listener() {
        return LISTENER;
    }
}
