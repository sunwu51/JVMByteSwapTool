package w.bridge;

/**
 * Bootstrap-visible callback contract implemented by the agent runtime.
 */
public interface DiagnosticListener {
    void info(Object message);

    void error(Object message, Throwable throwable);

    Object watchEnter(String id);

    void watchExit(Object token, long start, Object target, Object[] args,
                   Object result, Throwable throwable);

    void outerEnter(String id);

    void outerExit(String id);

    boolean outerShouldRecord(String id);

    Object outerCallEnter(String id);

    void outerCallExit(Object token, int line, long start, Object target, Object[] args,
                       Object result, Throwable throwable);

    Object traceEnter(String id);

    void traceRecursive(String id);

    void traceSubCall(String id, String key, long duration);

    void traceExit(Object token, long start);
}
