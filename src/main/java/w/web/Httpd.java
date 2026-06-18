package w.web;

import com.alibaba.fastjson2.JSON;
import fi.iki.elonen.NanoHTTPD;
import w.Global;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST;
import static fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR;
import static fi.iki.elonen.NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED;
import static fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND;
import static fi.iki.elonen.NanoHTTPD.Response.Status.OK;

/**
 * @author Frank
 * @date 2023/11/25 16:12
 */
public class Httpd extends NanoHTTPD {
    private static final String MCP_PATH = "/mcp";
    private static final String LOG_PATH = "/log";
    private static final String STATIC_ROOT = "/nanohttpd/";
    private static final String JSON_MIME = "application/json;charset=UTF-8";
    private static final String SSE_MIME = "text/event-stream;charset=UTF-8";

    private final McpDispatcher mcpDispatcher = new McpDispatcher();

    public Httpd(int port) {
        super(port);
    }

    public boolean isRunning() {
        return isAlive();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (method == Method.OPTIONS) {
            return withCors(newFixedLengthResponse(OK, MIME_PLAINTEXT, ""));
        }
        if (MCP_PATH.equals(uri)) {
            return serveMcp(session);
        }
        if (LOG_PATH.equals(uri)) {
            return serveLog(session);
        }
        return serveStatic(session);
    }

    private Response serveMcp(IHTTPSession session) {
        if (session.getMethod() != Method.POST) {
            return withCors(newFixedLengthResponse(METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "METHOD NOT ALLOWED"));
        }

        try {
            String body = readBody(session);
            if (body == null || body.trim().isEmpty()) {
                return withCors(newFixedLengthResponse(BAD_REQUEST, MIME_PLAINTEXT, "EMPTY BODY"));
            }
            return withCors(jsonResponse(OK, Global.toJson(mcpDispatcher.dispatch(body))));
        } catch (Exception e) {
            Global.error("mcp dispatch error:", e);
            return withCors(jsonResponse(INTERNAL_ERROR, Global.toJson(McpDispatcher.error(null, -32603, e.getMessage()))));
        }
    }

    private Response serveLog(IHTTPSession session) {
        if (session.getMethod() != Method.GET) {
            return withCors(newFixedLengthResponse(METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "METHOD NOT ALLOWED"));
        }

        return new SseResponse();
    }

    private Response serveStatic(IHTTPSession session) {
        if (session.getMethod() != Method.GET) {
            return withCors(newFixedLengthResponse(METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "METHOD NOT ALLOWED"));
        }

        String resourcePath = staticResourcePath(session.getUri());
        byte[] bytes = readClasspathResource(resourcePath);
        if (bytes == null && shouldFallbackToIndex(session.getUri())) {
            resourcePath = STATIC_ROOT + "index.html";
            bytes = readClasspathResource(resourcePath);
        }
        if (bytes == null) {
            return withCors(newFixedLengthResponse(NOT_FOUND, MIME_PLAINTEXT, "NOT FOUND"));
        }
        Response response = newFixedLengthResponse(OK, mimeType(resourcePath), new ByteArrayInputStream(bytes), bytes.length);
        response.addHeader("Cache-Control", resourcePath.endsWith("index.html") ? "no-cache" : "public, max-age=31536000");
        return withCors(response);
    }

    private String staticResourcePath(String uri) {
        String path = uri == null || uri.isEmpty() || "/".equals(uri) ? "index.html" : uri;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.contains("..")) {
            return STATIC_ROOT + "__not_found__";
        }
        return STATIC_ROOT + path;
    }

    private boolean shouldFallbackToIndex(String uri) {
        if (uri == null || uri.isEmpty() || "/".equals(uri)) {
            return true;
        }
        String path = uri.substring(uri.lastIndexOf('/') + 1);
        return !path.contains(".");
    }

    private byte[] readClasspathResource(String resourcePath) {
        try (InputStream input = Httpd.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            return output.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private String mimeType(String resourcePath) {
        String path = resourcePath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".html")) {
            return "text/html;charset=UTF-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript;charset=UTF-8";
        }
        if (path.endsWith(".css")) {
            return "text/css;charset=UTF-8";
        }
        if (path.endsWith(".json")) {
            return JSON_MIME;
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (path.endsWith(".ttf")) {
            return "font/ttf";
        }
        return "application/octet-stream";
    }

    private String toSseDataEvent(String message) {
        StringBuilder event = new StringBuilder();
        String[] lines = message.split("\\r?\\n", -1);
        for (String line : lines) {
            event.append("data: ").append(line).append('\n');
        }
        event.append('\n');
        return event.toString();
    }

    private Response jsonResponse(Response.IStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return newFixedLengthResponse(status, JSON_MIME, new ByteArrayInputStream(bytes), bytes.length);
    }

    private Response withCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }

    private String readBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body != null) {
            return body;
        }

        int contentLength = contentLength(session);
        if (contentLength <= 0) {
            return "";
        }
        byte[] bytes = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = session.getInputStream().read(bytes, offset, contentLength - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        return new String(bytes, 0, offset, StandardCharsets.UTF_8);
    }

    private int contentLength(IHTTPSession session) {
        try {
            String value = session.getHeaders().get("content-length");
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private class SseResponse extends Response {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

        private SseResponse() {
            super(OK, SSE_MIME, null, -1);
        }

        @Override
        protected void send(OutputStream output) {
            Global.addLogSubscriber(queue);
            try {
                writeHeaders(output);
                writeChunk(output, toSseDataEvent(connectedMessage()));
                while (!Thread.currentThread().isInterrupted()) {
                    String message = queue.poll(15, TimeUnit.SECONDS);
                    writeChunk(output, message == null ? ": ping\n\n" : toSseDataEvent(message));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                Global.removeLogSubscriber(queue);
            }
        }

        private void writeHeaders(OutputStream output) throws IOException {
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 ").append(OK.getDescription()).append(" \r\n");
            headers.append("Content-Type:").append(SSE_MIME).append("\r\n");
            headers.append("Date:").append(httpDate()).append("\r\n");
            headers.append("Connection:keep-alive\r\n");
            headers.append("Cache-Control:no-cache, no-transform\r\n");
            headers.append("X-Accel-Buffering:no\r\n");
            headers.append("Access-Control-Allow-Origin:*\r\n");
            headers.append("Access-Control-Allow-Methods:GET, POST, OPTIONS\r\n");
            headers.append("Access-Control-Allow-Headers:Content-Type\r\n");
            headers.append("Transfer-Encoding:chunked\r\n");
            headers.append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private String httpDate() {
            SimpleDateFormat format = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            return format.format(new Date());
        }

        private void writeChunk(OutputStream output, String event) throws IOException {
            byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
            output.write(Integer.toHexString(bytes.length).getBytes(StandardCharsets.UTF_8));
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private String connectedMessage() {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "LOG");
            message.put("id", "");
            message.put("level", 0);
            message.put("timestamp", System.currentTimeMillis());
            message.put("content", "log stream connected, subscribers=" + Global.logSubscriberCount());
            return JSON.toJSONString(message);
        }
    }
}
