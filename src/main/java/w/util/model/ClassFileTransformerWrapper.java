package w.util.model;

import lombok.Data;

import java.lang.instrument.ClassFileTransformer;

/**
 * @author Frank
 * @date 2023/12/21 23:57
 */
@Data
public class ClassFileTransformerWrapper {
    ClassFileTransformer transformer;
    int status;
    public ClassFileTransformerWrapper(ClassFileTransformer transformer) {
        this.transformer = transformer;
    }
}
