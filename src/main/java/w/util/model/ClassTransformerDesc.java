package w.util.model;

import lombok.Data;

import java.lang.instrument.ClassFileTransformer;

/**
 * @author Frank
 * @date 2023/12/21 11:54
 */
@Data
class ClassTransformerDesc implements TransformerDesc {
    TransformerType type = TransformerType.CLASS;

    String className;

    ClassFileTransformer transformer;

    public ClassTransformerDesc(String className, ClassFileTransformer transformer) {
        this.className = className;
        this.transformer = transformer;
    }
}
