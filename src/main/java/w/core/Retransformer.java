package w.core;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.lang.instrument.ClassFileTransformer;

/**
 * @author Frank
 * @date 2023/12/3 11:44
 */
@Data
@AllArgsConstructor
public class Retransformer {
    RetransformType type;
    ClassFileTransformer classFileTransformer;
}
