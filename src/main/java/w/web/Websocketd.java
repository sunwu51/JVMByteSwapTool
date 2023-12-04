package w.web;

import w.Global;
import w.core.MethodId;
import w.core.Swapper;
import w.web.message.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * @author Frank
 * @date 2023/11/25 16:46
 */

public class Websocketd extends NanoWSD {
    ObjectMapper objectMapper = new ObjectMapper();

    Swapper swapper = Swapper.getInstance();

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
                System.err.println("ws error " + e);
            }

            private void dispatch(String msg) {
                try {
                    if (!msg.contains("_")) {
                        System.out.println(msg);
                    }
                    Message message = objectMapper.readValue(msg, Message.class);
                    Global.socketCtx.set(this);
                    Global.traceIdCtx.set(message.getId());
                    if (!"_".equals(message.getId())) {
                        Global.socketMap.put(message.getId(), this);
                    }
                    switch (message.getType()) {
                        case PING:
                            PongMessage m = new PongMessage();
                            m.setId(message.getId());
                            this.send(objectMapper.writeValueAsString(m));
                            break;
                        case CHANGE_BODY:
                            ChangeBodyMessage changeBodyMessage = (ChangeBodyMessage) message;
                            MethodId methodId = new MethodId(changeBodyMessage.getClassName(), changeBodyMessage.getMethod(), changeBodyMessage.getParamTypes());
                            swapper.changeBody(methodId,changeBodyMessage.getBody());
                            break;
                        case WATCH:
                            WatchMessage watchMessage = (WatchMessage) message;
                            String[] arr = watchMessage.getSignature().split("#");
                            assert arr.length == 2;
                            methodId = new MethodId(arr[0], arr[1], null);
                            swapper.watch(methodId, watchMessage.isUseJson());
                            break;
                        case EXEC:
                            ExecMessage execMessage = (ExecMessage) message;
                            swapper.changeExec(execMessage.getBody());
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
