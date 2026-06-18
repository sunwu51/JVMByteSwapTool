package w.web.message;

import lombok.Data;

import java.util.UUID;

/**
 *
 * Message abstract class, to json ser/des
 * @author Frank
 * @date 2023/11/25 21:52
 */

@Data
public abstract class Message {
    protected MessageType type;
    protected String id = UUID.randomUUID().toString();

    protected long timestamp = System.currentTimeMillis();
}
