package com.example.vmproxy.web.message;

import lombok.Data;

import java.util.List;

@Data
public class ChangeBodyMessage extends Message {
    String className;
    String method;
    List<String> paramTypes;
    String body;
    {
        type = MessageType.CHANGE_BODY;
    }
}
