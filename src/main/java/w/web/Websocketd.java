package w.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import w.Global;
import w.core.ExecBundle;
import w.core.GroovyBundle;
import w.core.Swapper;
import w.util.RequestUtils;
import w.web.message.DeleteMessage;
import w.web.message.EvalMessage;
import w.web.message.ExecMessage;
import w.web.message.Message;
import w.web.message.MessageType;
import w.web.message.PongMessage;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * @author Frank
 * @date 2023/11/25 16:46
 */

public class Websocketd extends NanoWSD {
    Logger log = Logger.getLogger(Websocketd.class.getName());

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
                    Message message = parseMessage(msg);
                    if (message.getType() != MessageType.PING) {
                        log.info(Global.toJson(message));
                        RequestUtils.initRequestCtx(this, message.getId());
                    }
                    switch (message.getType()) {
                        case PING:
                            PongMessage m = new PongMessage();
                            m.setId(message.getId());
                            String json = Global.toJson(m);
                            this.send(json);
                            break;
                        case EXEC:
                            ExecMessage execMessage = (ExecMessage) message;
                            ExecBundle.changeBodyAndInvoke(execMessage.getBody());
                            break;
                        case EVAL:
                            EvalMessage evalMessage = (EvalMessage) message;
                            try {
                                Object res = GroovyBundle.eval(evalMessage.getBody());
                                Global.info((evalMessage.getBody().startsWith("!") ?
                                        "$ " + evalMessage.getBody().substring(1) : "groovy > " + evalMessage.getBody()) + "\n> " + res);
                            } catch (Exception e) {
                                Global.error(e.toString(), e);
                            }
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
                        case RESET:
                            Global.reset();
                            Global.info("reset finished");
                            break;
                        default:
                            swapper.swap(message);
                    }

                } catch (JSONException | IllegalArgumentException e) {
                    e.printStackTrace();
                    Global.error("not a valid message");
                } catch (Throwable e) {
                    Global.error("error:", e);
                } finally {
                    RequestUtils.clearRequestCtx();
                }
            }

            private Message parseMessage(String msg) {
                JSONObject jsonObject = JSON.parseObject(msg);
                MessageType type = jsonObject.getObject("type", MessageType.class);
                if (type == null) {
                    throw new IllegalArgumentException("message type is required");
                }
                switch (type) {
                    case REPLACE_CLASS:
                        return jsonObject.toJavaObject(w.web.message.ReplaceClassMessage.class);
                    case CHANGE_BODY:
                        return jsonObject.toJavaObject(w.web.message.ChangeBodyMessage.class);
                    case CHANGE_RESULT:
                        return jsonObject.toJavaObject(w.web.message.ChangeResultMessage.class);
                    case PING:
                        return jsonObject.toJavaObject(w.web.message.PingMessage.class);
                    case PONG:
                        return jsonObject.toJavaObject(w.web.message.PongMessage.class);
                    case WATCH:
                        return jsonObject.toJavaObject(w.web.message.WatchMessage.class);
                    case OUTER_WATCH:
                        return jsonObject.toJavaObject(w.web.message.OuterWatchMessage.class);
                    case EXEC:
                        return jsonObject.toJavaObject(w.web.message.ExecMessage.class);
                    case TRACE:
                        return jsonObject.toJavaObject(w.web.message.TraceMessage.class);
                    case DELETE:
                        return jsonObject.toJavaObject(w.web.message.DeleteMessage.class);
                    case RESET:
                        return jsonObject.toJavaObject(w.web.message.ResetMessage.class);
                    case DECOMPILE:
                        return jsonObject.toJavaObject(w.web.message.DecompileMessage.class);
                    case EVAL:
                        return jsonObject.toJavaObject(w.web.message.EvalMessage.class);
                    default:
                        throw new IllegalArgumentException("unsupported message type: " + type);
                }
            }
        };
    }
}
