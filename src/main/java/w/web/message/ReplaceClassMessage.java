package w.web.message;

import lombok.Data;

/**
 * request message to replace class
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class ReplaceClassMessage extends Message implements RequestMessage {
    {
        type = MessageType.REPLACE_CLASS;
    }

    String className;

    String content;
}
