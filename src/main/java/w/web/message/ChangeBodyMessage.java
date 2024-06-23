package w.web.message;

import lombok.Data;

import java.util.List;

/**
 * request message to change the java method body
 *
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class ChangeBodyMessage extends Message implements RequestMessage {
    String className;
    String method;
    List<String> paramTypes;
    String body;
    int mode;
    {
        type = MessageType.CHANGE_BODY;
    }
}
