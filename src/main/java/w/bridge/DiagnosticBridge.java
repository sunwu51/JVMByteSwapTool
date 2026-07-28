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

    public static Object debugEnter(String id) {
        try {
            DiagnosticListener current = listener;
            return current == null ? null : current.debugEnter(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void debugHit(String id, Object token, Object target, Object[] args) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.debugHit(id, token, target, args);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void debugHit(String id, Object token, Object target, Object[] args,
                                String[] localNames, String[] localTypes,
                                Object[] localValues) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.debugHit(id, token, target, args,
                        localNames, localTypes, localValues);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void debugCallHit(
            String id, Object token, Object target, Object[] methodArgs,
            String[] callTypes, Object[] callArgs,
            String[] localNames, String[] localTypes, Object[] localValues,
            Object result, Throwable throwable, boolean voidResult,
            boolean methodExitLine) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.debugCallHit(id, token, target, methodArgs,
                        callTypes, callArgs, localNames, localTypes, localValues,
                        result, throwable, voidResult, methodExitLine);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void debugExit(Object token, Object result, Throwable throwable,
                                 boolean voidResult) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.debugExit(token, result, throwable, voidResult);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }

    public static void debugBreakpointExit(Object token, Object result,
                                           Throwable throwable, boolean voidResult) {
        try {
            DiagnosticListener current = listener;
            if (current != null) {
                current.debugBreakpointExit(token, result, throwable, voidResult);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never change application control flow.
        }
    }
}
