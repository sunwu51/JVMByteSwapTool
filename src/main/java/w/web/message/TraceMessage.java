package w.web.message;

import lombok.Data;

@Data
public class TraceMessage extends ClassTargetMessage {
    {
        type = MessageType.TRACE;
    }

    /**
     * The method signature with format: com.example.A#func
     */
    String signature;

    int minCost;

    boolean ignoreZero;

    boolean includeNested = true;
}
