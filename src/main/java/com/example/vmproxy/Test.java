package com.example.vmproxy;

import com.example.vmproxy.web.message.Message;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * @author Frank
 * @date 2023/11/26 21:40
 */
public class Test {
    public static void main(String[] args) throws JsonProcessingException {
        String json = "{\n" +
                "    \"id\": \"111\",\n" +
                "    \"type\":\"WATCH\",\n" +
                "    \"signature\": \"com.example.vmproxy.A#run\"\n" +
                "}";

        Message m = Global.objectMapper.readValue(json, Message.class);
        System.out.println(m);
    }
}
