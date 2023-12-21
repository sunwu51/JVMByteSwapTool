package w.util.model;

import lombok.Data;

import java.lang.instrument.ClassFileTransformer;
import java.util.List;

/**
 * @author Frank
 * @date 2023/12/21 11:54
 */
@Data
class SingleMethodTransformerDesc implements TransformerDesc {
    TransformerType type = TransformerType.SINGLE_METHOD;

    String className;

    String method;

    List<String> paramTypes;

    ClassFileTransformer transformer;

    public SingleMethodTransformerDesc(String className, String method, List<String> paramTypes, ClassFileTransformer transformer) {
        this.className = className;
        this.method = method;
        this.paramTypes = paramTypes;
        this.transformer = transformer;
    }
}
