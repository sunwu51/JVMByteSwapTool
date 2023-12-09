package w.web.message;

import lombok.Data;

/**
 * Response message with the server log that happen in this request
 * @author Frank
 * @date 2023/11/25 22:51
 */
@Data
public class LogMessage extends Message {
    {
        type = MessageType.LOG;
    }
    String content;
}
