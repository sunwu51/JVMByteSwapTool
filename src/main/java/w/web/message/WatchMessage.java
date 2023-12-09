package w.web.message;

import lombok.Data;

/**
 * Watch method message
 * @author Frank
 * @date 2023/11/26 19:49
 */
@Data
public class WatchMessage extends Message implements RequestMessage {
    {
        type = MessageType.WATCH;
    }

    /**
     * The method signature with format: com.example.A#func
     */
    String signature;

    boolean useJson;
}
