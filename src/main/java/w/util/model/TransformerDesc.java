package w.util.model;

import java.lang.instrument.ClassFileTransformer;

/**
 * @author Frank
 * @date 2023/12/21 11:51
 */
public interface TransformerDesc {
    TransformerType getType();
    String getClassName();
    ClassFileTransformer getTransformer();
}

