package w.web.message;

import lombok.Data;

/**
 * Base message for operations that target one loaded class identity.
 */
@Data
public abstract class ClassTargetMessage extends Message {
    protected String classLoaderHash;
}
