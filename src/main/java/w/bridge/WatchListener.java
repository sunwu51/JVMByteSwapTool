package w.bridge;

/**
 * Bootstrap-visible callback implemented by the agent class loader.
 */
public interface WatchListener {
    Object enter(String id);

    void exit(Object token, long start, Object target, Object[] args, Object result, Throwable throwable);
}
