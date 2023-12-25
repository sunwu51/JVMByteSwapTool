package w.web.message;

import lombok.Data;
import w.Global;

import java.util.HashMap;
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
    Map<String, Map<String, List<String>>> content = new HashMap<>();
    {
        type = MessageType.PONG;
        synchronized (Global.class) {
            Global.activeTransformers.forEach((cls, loader2Transs) -> {
                loader2Transs.forEach((loader, transs) -> {
                    content.computeIfAbsent(cls, o -> new HashMap<>()).computeIfAbsent(loader, o -> transs.stream().map(trans -> trans.getTraceId() + "_" + trans.desc() +"_" + trans.getUuid()).collect(Collectors.toList()));
                });
            });
        }
    }
}
