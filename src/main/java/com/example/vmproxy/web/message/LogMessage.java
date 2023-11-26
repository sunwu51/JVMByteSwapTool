package com.example.vmproxy.web.message;

import lombok.Data;

/**
 * @author Frank
 * @date 2023/11/25 22:51
 */
@Data
public class LogMessage extends Message {
    {
        type = MessageType.LOG;
    }
    String content;
}
