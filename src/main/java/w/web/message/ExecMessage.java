package w.web.message;

import lombok.Data;

/**
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class ExecMessage extends Message {
    {
        type = MessageType.EXEC;
    }

    String body;
}
