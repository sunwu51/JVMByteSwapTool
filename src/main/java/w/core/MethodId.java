package w.core;


import lombok.Data;

import java.util.List;
import java.util.Objects;

@Data
public class MethodId {
    String className;

    String method;

    List<String> paramTypes;

    public MethodId(String className, String method, List<String> paramTypes) {
        this.className = className;
        this.method = method;
        this.paramTypes = paramTypes;
    }
}
