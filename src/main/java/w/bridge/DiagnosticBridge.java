package w.bridge;

/**
 * JDK-only bridge. Diagnostic callback failures never affect application control flow.
 */
public final class DiagnosticBridge {
    private static volatile DiagnosticListener listener;

    private DiagnosticBridge() {
    }

    public static void setListener(DiagnosticListener value) {
        listener = value;
    }

    public static void info(Object message) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.info(message);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void error(Object message) {
        error(message, null);
    }

    public static void error(Object message, Throwable throwable) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.error(message, throwable);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static Object watchEnter(String id) {
        try {
            DiagnosticListener current = listener;
            return current == null ? null : current.watchEnter(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void watchExit(Object token, long start, Object target, Object[] args,
                                 Object result, Throwable throwable) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.watchExit(token, start, target, args, result, throwable);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void outerEnter(String id) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.outerEnter(id);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void outerExit(String id) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.outerExit(id);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static boolean outerShouldRecord(String id) {
        try {
            DiagnosticListener current = listener;
            return current != null && current.outerShouldRecord(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object outerCallEnter(String id) {
        try {
            DiagnosticListener current = listener;
            return current == null ? null : current.outerCallEnter(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void outerCallExit(Object token, int line, long start, Object target,
                                     Object[] args, Object result, Throwable throwable) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.outerCallExit(token, line, start, target, args, result, throwable);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static Object traceEnter(String id) {
        try {
            DiagnosticListener current = listener;
            return current == null ? null : current.traceEnter(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void traceRecursive(String id) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.traceRecursive(id);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void traceSubCall(String id, String key, long duration) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.traceSubCall(id, key, duration);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void traceExit(Object token, long start) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.traceExit(token, start);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }
}
