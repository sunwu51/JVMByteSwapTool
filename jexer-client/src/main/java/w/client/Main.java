package w.client;

import java.net.URI;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 18000;
        String host = "localhost";
        if (args.length > 0) {
            port = Integer.parseInt(args[1]);
        }
        URI uri = URI.create("ws://" + host + ":" + port);
        new App(uri).connect();
    }
}
