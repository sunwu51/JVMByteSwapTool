package com.example.vmproxy.web.message;

import lombok.Data;

/**
 * @author Frank
 * @date 2023/11/26 19:49
 */
@Data
public class WatchMessage extends Message {
    {
        type = MessageType.WATCH;
    }

    String signature;
}
