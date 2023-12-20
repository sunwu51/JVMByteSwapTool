package w.web.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;

import java.util.UUID;

/**
 *
 * Message abstract class, to json ser/des
 * @author Frank
 * @date 2023/11/25 21:52
 */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReplaceClassMessage.class, name = "REPLACE_CLASS"),
        @JsonSubTypes.Type(value = ChangeBodyMessage.class, name = "CHANGE_BODY"),
        @JsonSubTypes.Type(value = ChangeResultMessage.class, name = "CHANGE_RESULT"),
        @JsonSubTypes.Type(value = PingMessage.class, name = "PING"),
        @JsonSubTypes.Type(value = PongMessage.class, name = "PONG"),
        @JsonSubTypes.Type(value = WatchMessage.class, name = "WATCH"),
        @JsonSubTypes.Type(value = OuterWatchMessage.class, name = "OUTER_WATCH"),
        @JsonSubTypes.Type(value = ExecMessage.class, name = "EXEC")
})
@Data
public abstract class Message {
    protected MessageType type;
    protected String id = UUID.randomUUID().toString();

    protected long timestamp = System.currentTimeMillis();
}
