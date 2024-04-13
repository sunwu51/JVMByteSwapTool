package w.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class App extends WebSocketClient {

    ObjectMapper mapper = new ObjectMapper();

    Tui tui;

    public App(URI serverUri) {
        super(serverUri);
        this.setConnectionLostTimeout(0);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("Opened new connection to " + getURI());
        System.out.println("Will open the tui");
        if (tui == null) {
            CompletableFuture.runAsync(() -> {
                try {
                    Ws ws = new Ws() {
                        @Override
                        public void send(Map<String, Object> map) {
                            try {
                                String json = mapper.writeValueAsString(map);
                                sendMessage(json);
                            } catch (JsonProcessingException e) {
                            }
                        }

                        @Override
                        public void close() {
                            App.this.close();
                        }
                    };
                    tui = new Tui(ws);
                    tui.run();
                } catch (Exception e) {
                    System.err.println("Open tui error!");
                    e.printStackTrace();
                }
            });

        }
    }

    @Override
    public void onMessage(String message) {
        if (tui != null) {
            Map<String, Object> json;
            try {
                json = mapper.readValue(message, new TypeReference<Map<String, Object>>() {
                });
                if (json.get("type").equals("LOG")) {
                    tui.showLog(json.get("content") + "");
                }
            } catch (Exception e) {
            }

        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (tui != null) {
            tui.exit();
        }
        System.out.println("Closed connection to " + getURI() + "; Code: " + code + " Reason: " + reason);
        System.exit(1);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println(ex);
    }

    public void sendMessage(String message) {
        send(message);
    }

}