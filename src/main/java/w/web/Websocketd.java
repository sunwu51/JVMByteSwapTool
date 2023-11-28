package w.web;

import w.Global;
import w.core.MethodId;
import w.core.Retransformer;
import w.web.message.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;

/**
 * @author Frank
 * @date 2023/11/25 16:46
 */

@Slf4j
public class Websocketd extends NanoWSD {
    ObjectMapper objectMapper = new ObjectMapper();

    Retransformer retransformer = Retransformer.getInstance();

    public Websocketd(int port) {
        super(port);
    }
    @Override
    protected WebSocket openWebSocket(IHTTPSession ihttpSession) {
        return new WebSocket(ihttpSession) {
            @Override
            protected void onOpen() {}
            @Override
            protected void onClose(WebSocketFrame.CloseCode closeCode, String s, boolean b) {}

            @Override
            protected void onMessage(WebSocketFrame frame) {
                frame.setUnmasked();
                String msg = frame.getTextPayload();
                dispatch(msg);
//                WebSocketFrame toSend = new WebSocketFrame(WebSocketFrame.OpCode.Text, true, msg.getBytes());
//                try {
//                    sendFrame(toSend);
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
            }

            @Override
            protected void onPong(WebSocketFrame webSocketFrame) {
                System.out.println("P " + webSocketFrame);
            }

            @Override
            protected void onException(IOException e) {
                log.error("ws error", e);
            }

            private void dispatch(String msg) {
                try {
                    if (!msg.contains("_")) {
                        System.out.println(msg);
                    }
                    Message message = objectMapper.readValue(msg, Message.class);
                    Global.socketCtx.set(this);
                    Global.traceIdCtx.set(message.getId());
                    switch (message.getType()) {
                        // 心跳包
                        case PING:
                            PongMessage m = new PongMessage();
                            m.setId(message.getId());
                            this.send(objectMapper.writeValueAsString(m));
                            break;
                        // 修改方法的body
                        case CHANGE_BODY:
                            ChangeBodyMessage changeBodyMessage = (ChangeBodyMessage) message;
                            MethodId methodId = new MethodId(changeBodyMessage.getClassName(), changeBodyMessage.getMethod(), changeBodyMessage.getParamTypes());
                            retransformer.changeBody(methodId,changeBodyMessage.getBody());
                            break;
                        // 监控方法的执行时间和入参返回值
                        case WATCH:
                            WatchMessage watchMessage = (WatchMessage) message;
                            String[] arr = watchMessage.getSignature().split("#");
                            assert arr.length == 2;
                            methodId = new MethodId(arr[0], arr[1], null);
                            retransformer.watch(methodId);
                            break;
                        case EXEC:
                            ExecMessage execMessage = (ExecMessage) message;
                            retransformer.changeExec(execMessage.getBody());
                            Global.exec();
                            break;
                        default:
                            Global.info("message type not support");
                    }
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                    Global.info("not a valid message");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    Global.socketCtx.remove();
                    Global.traceIdCtx.remove();
                }
            }
        };
    }
}
