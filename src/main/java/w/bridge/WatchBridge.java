package w.bridge;

/**
 * JDK-only bridge. Every callback failure is isolated from the watched method.
 */
public final class WatchBridge {
    private static volatile WatchListener listener;

    private WatchBridge() {
    }

    public static void setListener(WatchListener value) {
        listener = value;
    }

    public static Object enter(String id) {
        try {
            WatchListener current = listener;
            return current == null ? null : current.enter(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void exit(Object token, long start, Object target, Object[] args,
                            Object result, Throwable throwable) {
        try {
            WatchListener current = listener;
            if (current != null) {
                current.exit(token, start, target, args, result, throwable);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }
}
