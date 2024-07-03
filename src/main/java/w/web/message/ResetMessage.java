package w.web.message;

import lombok.Data;

/**
 * request message to execute some code in a new thread
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class ResetMessage extends Message implements RequestMessage {
    {
        type = MessageType.RESET;
    }
}
