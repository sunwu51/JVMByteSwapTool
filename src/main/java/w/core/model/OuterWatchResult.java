package w.core.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OuterWatchResult {
    private List<InspectedCall> inspectedCalls = new ArrayList<>();

    @Data
    public static class InspectedCall {
        private int line;
        private String outerSignature;
        private String outerDescriptor;
        private String innerSignature;
        private String innerDescriptor;
        private String outerDisplay;
    }
}
