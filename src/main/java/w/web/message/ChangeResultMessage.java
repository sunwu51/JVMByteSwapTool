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
public class ChangeResultMessage extends Message implements RequestMessage {
    String className;
    String method;
    List<String> paramTypes;
    String innerClassName;
    String innerMethod;
    String body;
    int mode;
    {
        type = MessageType.CHANGE_RESULT;
    }
}
