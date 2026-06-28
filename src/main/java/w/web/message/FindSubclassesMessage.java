package w.web.message;

import lombok.Data;

/**
 * request message to find loaded subclasses or implementations
 */
@Data
public class FindSubclassesMessage extends Message implements RequestMessage {
    {
        type = MessageType.FIND_SUBCLASSES;
    }

    String className;
}
