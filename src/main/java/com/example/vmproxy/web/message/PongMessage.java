package com.example.vmproxy.web.message;

import lombok.Data;

/**
 * @author Frank
 * @date 2023/11/25 22:08
 */
@Data
public class PongMessage extends Message {
    {
        type = MessageType.PONG;
    }
}
