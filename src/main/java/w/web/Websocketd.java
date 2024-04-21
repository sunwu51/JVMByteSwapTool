package w.web;

import w.Global;
import w.core.ExecBundle;
import w.core.Swapper;
import w.util.RequestUtils;
import w.web.message.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * @author Frank
 * @date 2023/11/25 16:46
 */

public class Websocketd extends NanoWSD {
    Logger log = Logger.getLogger(Websocketd.class.getName());

    ObjectMapper objectMapper = new ObjectMapper();

    Swapper swapper = Swapper.getInstance();

    public Websocketd(int port) {
        super(port);
    }
    @Override
    protected WebSocket openWebSocket(IHTTPSession ihttpSession) {
        return new WebSocket(ihttpSession) {
            @Override
            protected void onOpen() {
                Global.addWs(this);
            }
            @Override
            protected void onClose(WebSocketFrame.CloseCode closeCode, String s, boolean b) {
                Global.removeWs(this);
            }

            @Override
            protected void onMessage(WebSocketFrame frame) {
                Global.addWs(this);
                frame.setUnmasked();
                String msg = frame.getTextPayload();
                dispatch(msg);
            }

            @Override
            protected void onPong(WebSocketFrame webSocketFrame) {}

            @Override
            protected void onException(IOException e) {
                if (!this.isOpen()) {
                    System.out.println("ws closed");
                    Global.removeWs(this);
                }
            }

            private void dispatch(String msg) {
                try {
                    Message message = objectMapper.readValue(msg, Message.class);
                    if (message.getType() != MessageType.PING) {
                        log.info(objectMapper.writeValueAsString(message));
                        RequestUtils.initRequestCtx(this, message.getId());
                    }
                    switch (message.getType()) {
                        case PING:
                            PongMessage m = new PongMessage();
                            m.setId(message.getId());
                            String json = objectMapper.writeValueAsString(m);
                            this.send(json);
                            break;
                        case EXEC:
                            ExecMessage execMessage = (ExecMessage) message;
                            ExecBundle.changeBodyAndInvoke(execMessage.getBody());
                            break;
                        case DELETE:
                            DeleteMessage deleteMessage = (DeleteMessage) message;
                            if (deleteMessage.getUuid() != null) {
                                try {
                                    Global.deleteTransformer(UUID.fromString(deleteMessage.getUuid()));
                                } catch (Exception e) {
                                    Global.error("delete error:", e);
                                }
                            }
                            break;
                        default:
                            swapper.swap(message);
                    }

                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                    Global.error("not a valid message");
                } catch (Throwable e) {
                    Global.error("error:", e);
                } finally {
                    RequestUtils.clearRequestCtx();
                }
            }
        };
    }
}
