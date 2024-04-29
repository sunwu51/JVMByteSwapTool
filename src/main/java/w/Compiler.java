package w;

import fi.iki.elonen.NanoHTTPD;
import w.core.InMemoryJavaCompiler;
import w.util.PrintUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST;

public class Compiler {
    public static final int port = 13019;

    public static void main(String[] args) throws IOException {
        try {
            Class.forName("com.sun.tools.javac.processing.JavacProcessingEnvironment");
        } catch (ClassNotFoundException e) {
            System.err.println("Please use jdk instead of jre to start a compiler server");
            System.exit(-1);
        }
        NanoHTTPD nanoHTTPD = new NanoHTTPD(port) {
            @Override
            public Response serve(String uri, Method method,
                                  Map<String, String> header, Map<String, String> parameters,
                                  Map<String, String> files) {
                if (method == Method.POST) {
                    switch (uri) {
                        case "/compile":
                            String className = parameters.get("className");

                            String content = parameters.get("content");
                            content = new String(Base64.getDecoder().decode(parameters.get("content")), StandardCharsets.UTF_8);
                            try {
                                StringBuilder errorMessage = new StringBuilder();
                                byte[] bytecode = InMemoryJavaCompiler.compile(className, content, new InMemoryJavaCompiler.InMemoryDiagnosticListener(errorMessage));
                                Map<String, Object> _m = new HashMap<>();
                                if (bytecode != null) {
                                    _m.put("code", 0);
                                    _m.put("data", Base64.getEncoder().encodeToString(bytecode) );
                                    String json = PrintUtils.getObjectMapper().writeValueAsString(_m);
                                    return newFixedLengthResponse(json);
                                } else {
                                    _m.put("code", 1);
                                    _m.put("data", errorMessage.toString());
                                    String json = PrintUtils.getObjectMapper().writeValueAsString(_m);
                                    return newFixedLengthResponse(json);
                                }
                            } catch (Exception e) {
                                return newFixedLengthResponse(BAD_REQUEST, "", "NOT SUPPORT");
                            }
                    }
                }

                return newFixedLengthResponse(BAD_REQUEST, "", "NOT SUPPORT");
            }
        };
        nanoHTTPD.start(5000, false);
    }
}


