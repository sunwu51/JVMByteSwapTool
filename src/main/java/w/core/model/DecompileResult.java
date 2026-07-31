package w.core.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecompileResult {
    private String className;
    private String source;
    private int[] lineMapping;
    private List<InvocationSite> invocations = new ArrayList<>();

    @Data
    public static class InvocationSite {
        private String declaringClass;
        private String outerMethod;
        private String outerDescriptor;
        private int originalLine;
        private Integer decompiledLine;
        private String opcode;
        private String owner;
        private String name;
        private String descriptor;
        private boolean interfaceCall;

        public String getOuterSignature() {
            return declaringClass + "#" + outerMethod;
        }

        public String getInnerSignature() {
            return owner + "#" + name;
        }
    }
}
