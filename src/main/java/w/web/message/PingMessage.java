package w.web.message;

import lombok.Data;

/**
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class PingMessage extends Message {
    {
        type = MessageType.PING;
    }
}
