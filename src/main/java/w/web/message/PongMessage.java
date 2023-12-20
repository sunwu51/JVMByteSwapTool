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
public class PongMessage extends Message implements ResponseMessage {
    List<String> activeMethods = new ArrayList<>();
    {
        type = MessageType.PONG;
        for (Map<MethodId, Retransformer> m : Global.traceId2MethodId2Trans.values()) {
            activeMethods.addAll(m.keySet().stream().map(it -> it.getClassName() + "#" + it.getMethod())
                    .collect(Collectors.toSet()));
        }
    }
}
