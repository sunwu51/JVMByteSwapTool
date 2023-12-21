package w.web;

import w.Global;
import w.core.MethodId;
import w.core.Swapper;
import w.util.RequestUtils;
import w.web.message.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Base64;

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
                    Message message = objectMapper.readValue(msg, Message.class);
                    if (message.getType() != MessageType.PING) {
                        System.out.println(objectMapper.writeValueAsString(message));
                        RequestUtils.initRequestCtx(this, message.getId());
                    }

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
                        case CHANGE_RESULT:
                            ChangeResultMessage changeResultMessage = (ChangeResultMessage) message;
                            methodId = new MethodId(changeResultMessage.getClassName(), changeResultMessage.getMethod(), changeResultMessage.getParamTypes());
                            MethodId inner = new MethodId(changeResultMessage.getInnerClassName(), changeResultMessage.getInnerMethod(), null);
                            swapper.changeResult(methodId, inner, changeResultMessage.getBody());
                            break;
                        case WATCH:
                            WatchMessage watchMessage = (WatchMessage) message;
                            String[] arr = watchMessage.getSignature().split("#");
                            assert arr.length == 2;
                            methodId = new MethodId(arr[0], arr[1], null);
                            swapper.watch(methodId, watchMessage.isUseJson());
                            break;
                        case OUTER_WATCH:
                            OuterWatchMessage outerWatchMessage = (OuterWatchMessage) message;
                            String[] arr2 = outerWatchMessage.getSignature().split("#");
                            assert arr2.length == 2;
                            String[] arr3 = outerWatchMessage.getInnerSignature().split("#");
                            assert arr3.length == 2;
                            methodId = new MethodId(arr2[0], arr2[1], null);
                            inner = new MethodId(arr3[0], arr3[1], null);
                            swapper.outerWatch(methodId, inner, outerWatchMessage.isUseJson());
                            break;
                        case EXEC:
                            ExecMessage execMessage = (ExecMessage) message;
                            Global.execBundle.changeBodyAndInvoke(execMessage.getBody());
                            break;
                        case REPLACE_CLASS:
                            ReplaceClassMessage replaceClassMessage = (ReplaceClassMessage) message;
                            byte[] content = Base64.getDecoder().decode(replaceClassMessage.getContent());
                            swapper.replaceClass(replaceClassMessage.getClassName(), content);
                            break;
                        default:
                            Global.log(2, "message type not support");
                    }

                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                    Global.log(2, "not a valid message");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    Global.socketCtx.remove();
                    Global.traceIdCtx.remove();
                    Global.classToLoader.remove();
                }
            }
        };
    }
}
