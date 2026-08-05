package w.core.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChangeResultResult {
    private List<EnhancedCall> enhancedCalls = new ArrayList<>();

    @Data
    public static class EnhancedCall {
        private int line;
        private String outerSignature;
        private String outerDescriptor;
        private String innerSignature;
        private String innerDescriptor;
    }
}
