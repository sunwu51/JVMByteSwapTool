package w.web;

import fi.iki.elonen.NanoHTTPD;
import lombok.extern.slf4j.Slf4j;
import w.Global;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;

/**
 * @author Frank
 * @date 2023/11/25 16:12
 */
@Slf4j
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
            return serveFile(uri);
        }
        if (method == Method.POST) {
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
        byte[] content = new byte[40960];
        int len = 0;
        try (InputStream in = this.getClass().getResourceAsStream("/nanohttpd" + fileName)) {
            assert in != null;
            len = in.read(content);
        } catch (Exception e) {
            log.debug("file {} not found in the resource, try to find it from current dir", fileName);
            try (FileInputStream in = new FileInputStream(fileName.substring(1))) {
                len = in.read(content);
            } catch (Exception ex) {
                return newFixedLengthResponse(NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "NOT FOUND");
            }
        }
        return newFixedLengthResponse(OK, NanoHTTPD.MIME_HTML, new String(content, 0, len));
    }
}
