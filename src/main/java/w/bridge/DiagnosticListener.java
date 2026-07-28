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

    /**
     * Optional Online Debug callback. Kept default for binary/source compatibility
     * with listeners compiled before Online Debug was added.
     */
    default Object debugEnter(String id) {
        return null;
    }

    void debugHit(String id, Object token, Object target, Object[] args);

    default void debugHit(String id, Object token, Object target, Object[] args,
                          String[] localNames, String[] localTypes, Object[] localValues) {
        debugHit(id, token, target, args);
    }

    default void debugCallHit(String id, Object token, Object target, Object[] methodArgs,
                              String[] callTypes, Object[] callArgs,
                              String[] localNames, String[] localTypes, Object[] localValues,
                              Object result, Throwable throwable, boolean voidResult,
                              boolean methodExitLine) {
        debugHit(id, token, target, callArgs,
                localNames, localTypes, localValues);
        if (!methodExitLine) {
            debugBreakpointExit(token, result, throwable, voidResult);
        }
    }

    /** Optional Online Debug callback. */
    default void debugExit(Object token, Object result, Throwable throwable,
                           boolean voidResult) {
    }

    /** Optional Online Debug callback. */
    default void debugBreakpointExit(Object token, Object result, Throwable throwable,
                                     boolean voidResult) {
    }
}
