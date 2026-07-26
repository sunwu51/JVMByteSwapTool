package w.core.diagnostic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OuterWatchConfig {
    private final String id;
    private final String traceId;
    private final String methodSignature;
    private final int printFormat;
    private final String ognl;
    private final int depthForJson;
    private final Map<String, String> variables;

    public OuterWatchConfig(String id, String traceId, String methodSignature, int printFormat,
                            String ognl, int depthForJson, Map<String, String> variables) {
        this.id = id;
        this.traceId = traceId;
        this.methodSignature = methodSignature;
        this.printFormat = printFormat;
        this.ognl = ognl;
        this.depthForJson = depthForJson;
        this.variables = variables == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
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

    public int getPrintFormat() {
        return printFormat;
    }

    public String getOgnl() {
        return ognl;
    }

    public int getDepthForJson() {
        return depthForJson;
    }

    public Map<String, String> getVariables() {
        return variables;
    }
}
