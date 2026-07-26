package w.core.diagnostic;

public final class TraceConfig {
    private final String id;
    private final String traceId;
    private final String methodSignature;
    private final long minCost;
    private final boolean ignoreZero;

    public TraceConfig(String id, String traceId, String methodSignature,
                       long minCost, boolean ignoreZero) {
        this.id = id;
        this.traceId = traceId;
        this.methodSignature = methodSignature;
        this.minCost = minCost;
        this.ignoreZero = ignoreZero;
    }

    public String getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public long getMinCost() {
        return minCost;
    }

    public boolean isIgnoreZero() {
        return ignoreZero;
    }
}
