package com.example.vmproxy;

import com.example.vmproxy.web.message.LogMessage;
import com.example.vmproxy.web.message.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class Global {
    public static Instrumentation instrumentation;

    public final static ThreadLocal<NanoWSD.WebSocket> socketCtx = new ThreadLocal<>();
    public final static ThreadLocal<String> traceIdCtx = new ThreadLocal<>();

    public final static ObjectMapper objectMapper = new ObjectMapper();

    public final static Map<String, NanoWSD.WebSocket> socketMap = new ConcurrentHashMap<>();

    public static void info(String content) {
        log.info(content);

        if (socketCtx.get() == null && traceIdCtx.get() != null) {
            NanoWSD.WebSocket ws = socketMap.get(traceIdCtx.get());
            socketCtx.set(ws);
        }
        System.out.println(traceIdCtx.get());
        System.out.println(socketMap);
        System.out.println(socketMap.get(traceIdCtx.get()));

        if (socketCtx.get() != null && socketCtx.get().isOpen()) {
            try {
                LogMessage message = new LogMessage();
                message.setId(traceIdCtx.get());
                message.setContent(content);
                socketCtx.get().send(objectMapper.writeValueAsString(message));
            } catch (IOException e) {
                log.error("send message error", e);
            }
        }
    }

    public static void main(String[] args) throws JsonProcessingException {
        Message m = objectMapper.readValue("{\"id\":\"4408e127-f882-4ebb-9184-9e4ce5450c49\",\"type\":\"CHANGE_BODY\",\"paramTypes\":[],\"body\":\"{System.out.println(\\\"A$run\\\" + new java.util.Date());}\",\"className\":\"attach.A\",\"method\":\"run\"}", Message.class);
        System.out.println(m);
    }
}
