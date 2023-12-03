package w.web.message;

import lombok.Data;
import w.Global;
import w.core.MethodId;
import w.core.Retransformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Heartbeats message
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class PingMessage extends Message implements RequestMessage {
    {
        type = MessageType.PING;
    }
}
