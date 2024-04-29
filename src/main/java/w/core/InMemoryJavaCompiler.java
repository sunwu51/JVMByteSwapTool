package w.core;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class InMemoryJavaCompiler {
    private static JavaCompiler compiler;
    private static StandardJavaFileManager stdFileManager;

    static  {
        compiler = ToolProvider.getSystemJavaCompiler();
        stdFileManager = compiler.getStandardFileManager(null, Locale.ENGLISH, StandardCharsets.UTF_8);
    }

    public static class InMemoryDiagnosticListener implements DiagnosticListener<JavaFileObject> {
        final StringBuilder sb;

        public InMemoryDiagnosticListener(StringBuilder sb) {
            this.sb = sb;
        }
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            sb.append("Line Number->" + diagnostic.getLineNumber() + "\n");
            sb.append("Code->" + diagnostic.getCode() + "\n");
            sb.append("Message->" + diagnostic.getMessage(null) + "\n");
            sb.append("Source->" + diagnostic.getSource() + "\n");
        }
    }

    public static byte[] compile(String className, String sourceCodeInText, InMemoryDiagnosticListener inMemoryDiagnosticListener) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String pathSeparator = System.getProperty("path.separator");


        JavaFileManager fileManager = new ForwardingJavaFileManager<JavaFileManager>(stdFileManager) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
                if (kind == JavaFileObject.Kind.CLASS) {
                    return new SimpleJavaFileObject(URI.create("byte:///" + className.replace('.', '/') + kind.extension), kind) {
                        public OutputStream openOutputStream() throws IOException {
                            return baos;
                        }
                    };
                } else {
                    return super.getJavaFileForOutput(location, className, kind, sibling);
                }
            }
        };

        // sourcecode convert to JavaFileObject
        JavaFileObject javaFileObject = new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return sourceCodeInText;
            }
        };

        // compile
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, inMemoryDiagnosticListener, null, null, Collections.singletonList(javaFileObject));

        boolean result = task.call();
        if (!result) {
            return null;
        }

        // the bytecode
        byte[] classBytes = baos.toByteArray();
        return classBytes;
    }
}
