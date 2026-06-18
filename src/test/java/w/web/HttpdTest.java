package w.web;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.Global;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class HttpdTest {

    @Test
    public void mcpShouldReturnToolsList() throws Exception {
        int port = freePort();
        Httpd httpd = new Httpd(port);
        httpd.start();
        try {
            HttpURLConnection connection = post(port, "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":\"tools\",\"method\":\"tools/list\"}");

            Assertions.assertEquals(200, connection.getResponseCode());
            String body = read(connection.getInputStream());
            Assertions.assertTrue(body.contains("\"jsonrpc\":\"2.0\""));
            Assertions.assertTrue(body.contains("\"tools\""));
            Assertions.assertTrue(body.contains("\"watch\""));
        } finally {
            httpd.stop();
        }
    }

    @Test
    public void rootShouldServeBundledUi() throws Exception {
        int port = freePort();
        Httpd httpd = new Httpd(port);
        httpd.start();
        try {
            HttpURLConnection connection = get(port, "/");

            Assertions.assertEquals(200, connection.getResponseCode());
            Assertions.assertTrue(connection.getContentType().startsWith("text/html"));
            String body = read(connection.getInputStream());
            Assertions.assertTrue(body.contains("<div id=\"root\"></div>"));
            Assertions.assertTrue(body.contains("./assets/index.js"));
        } finally {
            httpd.stop();
        }
    }

    @Test
    public void assetsShouldServeBundledUiFiles() throws Exception {
        int port = freePort();
        Httpd httpd = new Httpd(port);
        httpd.start();
        try {
            HttpURLConnection connection = get(port, "/assets/index.css");

            Assertions.assertEquals(200, connection.getResponseCode());
            Assertions.assertTrue(connection.getContentType().startsWith("text/css"));
            Assertions.assertTrue(read(connection.getInputStream()).contains("body"));
        } finally {
            httpd.stop();
        }
    }

    @Test
    public void logShouldStreamGlobalLogs() throws Exception {
        int port = freePort();
        Httpd httpd = new Httpd(port);
        httpd.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> firstLine = executor.submit(() -> {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/log").openConnection();
                connection.setRequestMethod("GET");
                connection.setReadTimeout(5000);
                Assertions.assertEquals(200, connection.getResponseCode());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ") && line.contains("nanohttpd log stream smoke")) {
                            return line;
                        }
                    }
                    return null;
                }
            });

            Thread.sleep(300);
            Global.info("nanohttpd log stream smoke");

            String line = firstLine.get(5, TimeUnit.SECONDS);
            Assertions.assertNotNull(line);
            Assertions.assertTrue(line.startsWith("data: "));
            Assertions.assertTrue(line.contains("nanohttpd log stream smoke"));
        } finally {
            executor.shutdownNow();
            httpd.stop();
        }
    }

    private HttpURLConnection get(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("GET");
        return connection;
    }

    private HttpURLConnection post(int port, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private String read(InputStream input) throws Exception {
        try (InputStream in = input) {
            byte[] buffer = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int len;
            while ((len = in.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    private int freePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
