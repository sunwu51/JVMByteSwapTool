package w.util.model;

import lombok.Data;

import java.lang.instrument.ClassFileTransformer;

/**
 * @author Frank
 * @date 2023/12/21 11:54
 */
@Data
class MultiMethodTransformerDesc implements TransformerDesc {
    TransformerType type = TransformerType.MULTI_METHOD;

    String className;

    String method;

    ClassFileTransformer transformer;

    public MultiMethodTransformerDesc(String className, String method, ClassFileTransformer transformer) {
        this.className = className;
        this.method = method;
        this.transformer = transformer;
    }
}
