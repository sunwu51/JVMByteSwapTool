package w.web;

import fi.iki.elonen.NanoHTTPD;
import lombok.extern.slf4j.Slf4j;
import w.Global;
import w.core.compiler.WCompiler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;

/**
 * @author Frank
 * @date 2023/11/25 16:12
 */
public class Httpd extends NanoHTTPD {

    public Httpd(int port) {
        super(port);
    }

    /**
     *
     * @param uri
     *            Percent-decoded URI without parameters, for example
     *            "/index.cgi"
     * @param method
     *            "GET", "POST" etc.
     * @param header
     *            Header entries, percent decoded
     * @param parameters
     *            Parsed, percent decoded parameters from URI and, in case of
     *            POST, form data.
     * @param files
     *            POST json body, the key is postData, value is body string
     * @return
     */
    @Override
    public Response serve(String uri, Method method,
                          Map<String, String> header, Map<String, String> parameters,
                          Map<String, String> files) {
        if (method == Method.GET) {
            // deprecated, plz use ws reset
            if (uri.equals("/reset")) {
                Global.reset();
                return newFixedLengthResponse("ok");
            }
            return serveFile(uri);
        }
        if (method == Method.POST) {
            // deprecated, use the ui config port
            switch (uri) {
                case "/wsPort":
                    return newFixedLengthResponse(Global.wsPort + "");
            }
        }

        return newFixedLengthResponse(BAD_REQUEST, "", "NOT SUPPORT POST");
    }

    private Response serveFile(String fileName) {
        if (fileName == null) {
            return newFixedLengthResponse(NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "NOT FOUND");
        }

        // default lead to index.html
        if (fileName.isEmpty() || fileName.equals("/")) {
            fileName = "/index.html";
        }
        fileName = fileName.startsWith("/") ? fileName : ("/" + fileName);
        byte[] content = new byte[409600]; //400k of content
        int len = 0;

        String res = "";
        try (InputStream in = this.getClass().getResourceAsStream("/nanohttpd" + fileName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                res = reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return newFixedLengthResponse(NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "NOT FOUND");
        }
        String mimeType = MIME_HTML;
        if (fileName.endsWith(".js")) {
            mimeType = "application/javascript";
        } else if (fileName.endsWith(".css")) {
            mimeType = "text/css";
        }
        return newFixedLengthResponse(OK, mimeType, res);
    }
}
