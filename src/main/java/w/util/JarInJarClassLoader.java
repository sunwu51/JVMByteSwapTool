package w.util;

import w.core.GroovyBundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/**
 * @author Frank
 * @date 2025/05/20 01:07
 */
public class JarInJarClassLoader extends URLClassLoader {
    private final static String PROTOCOL = "nestedjar";
    /**
     * The root jar url, e.g. swapper.jar
     */
    private final URL rootJarUrl;

    /**
     * The root jar file
     */
    private final JarFile rootJarFile;


    private final List<NestedJarEntry> nestedJars = new ArrayList<>();

    public JarInJarClassLoader(URL jarUrl, String entryPrefix, ClassLoader parent) throws IOException {
        super(new URL[0], parent);
        this.rootJarUrl = jarUrl;
        this.rootJarFile = new JarFile(jarUrl.getFile());
        // Search all jars in rootJar!/entryPrefix/xx.jar, add to nestedJars
        // And create nestedJarUrl add to the URLClassLoader.
        Enumeration<JarEntry> entries = rootJarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().startsWith(entryPrefix) && entry.getName().endsWith(".jar")) {
                NestedJarEntry nestedJar = new NestedJarEntry(rootJarFile, entry);
                nestedJars.add(nestedJar);
                // URL: root.jar!/W-INF/lib/dep1.jar
                URL nestedJarUrl = new URL(PROTOCOL, "", -1,
                        rootJarUrl + "!/" + entry.getName(), new Handler());
                addURL(nestedJarUrl);
            }
        }
    }
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            String path = name.replace('.', '/') + ".class";
            // Search class from every nestedJar
            for (NestedJarEntry nestedJar : nestedJars) {
                try {
                    byte[] classData = nestedJar.getClassData(path);
                    if (classData != null) {
                        return defineClass(name, classData, 0, classData.length,
                                new CodeSource(new URL(rootJarUrl, nestedJar.getEntryName()), (Certificate[]) null));
                    }
                } catch (IOException ex) {
                    continue;
                }
            }
            throw e;
        }
    }

    @Override
    public URL findResource(String name) {
        URL resource = super.findResource(name);
        if (resource != null) {
            return resource;
        }
        for (NestedJarEntry nestedJar : nestedJars) {
            if (nestedJar.containsEntry(name)) {
                try {
                    return new URL("nestedjar", "", -1,
                            rootJarUrl.toString() + "!/" + nestedJar.getEntryName() + "!/" + name, null);
                } catch (MalformedURLException e) {
                    continue;
                }
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        final Enumeration<URL> superResources = super.findResources(name);
        final List<URL> resources = new ArrayList<>();
        while (superResources.hasMoreElements()) {
            resources.add(superResources.nextElement());
        }
        for (NestedJarEntry nestedJar : nestedJars) {
            if (nestedJar.containsEntry(name)) {
                try {
                    URL resourceUrl = new URL(PROTOCOL, "", -1,
                            rootJarUrl.toString() + "!/" + nestedJar.getEntryName() + "!/" + name, new Handler());
                    resources.add(resourceUrl);
                } catch (MalformedURLException e) {
                    continue;
                }
            }
        }

        return new Enumeration<URL>() {
            private final Iterator<URL> iterator = resources.iterator();
            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }
            @Override
            public URL nextElement() {
                return iterator.next();
            }
        };
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            rootJarFile.close();
        }
    }

    private static class NestedJarEntry {
        private final JarFile rootJarFile;
        private final JarEntry jarEntry;
        private byte[] cachedContent;

        public NestedJarEntry(JarFile rootJarFile, JarEntry jarEntry) {
            this.rootJarFile = rootJarFile;
            this.jarEntry = jarEntry;
        }

        public String getEntryName() {
            return jarEntry.getName();
        }

        public byte[] getContent() throws IOException {
            if (cachedContent == null) {
                try (InputStream is = rootJarFile.getInputStream(jarEntry)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    cachedContent = baos.toByteArray();
                }
            }
            return cachedContent;
        }

        public byte[] getClassData(String classPath) throws IOException {
            JarEntryReader reader = new JarEntryReader(getContent());
            return reader.getEntryContent(classPath);
        }

        public boolean containsEntry(String entryName) {
            try {
                JarEntryReader reader = new JarEntryReader(getContent());
                return reader.containsEntry(entryName);
            } catch (IOException e) {
                return false;
            }
        }
    }
    private static class JarEntryReader {
        private final byte[] jarContent;

        public JarEntryReader(byte[] jarContent) {
            this.jarContent = jarContent;
        }

        public byte[] getEntryContent(String entryName) throws IOException {
            try (InputStream is = new java.io.ByteArrayInputStream(jarContent);
                 java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(is)) {

                JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    if (entryName.equals(entry.getName())) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = jis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        return baos.toByteArray();
                    }
                }
            }
            return null;
        }

        public boolean containsEntry(String entryName) throws IOException {
            try (InputStream is = new java.io.ByteArrayInputStream(jarContent);
                 java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(is)) {

                JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    if (entryName.equals(entry.getName())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
class Handler extends URLStreamHandler {

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        return new NestedJarURLConnection(url);
    }
    private static class NestedJarURLConnection extends URLConnection {
        private static final String JAR_URL_SEPARATOR = "!/";

        private String rootJarPath;
        private String entryPath;
        private String resourcePath;

        /**
         * if the URL is c:/app.jar!/W-INF/lib/groovy.jar!/org/apache/groovy/json/FastStringService.class
         * rootJarPath:  c:/app.jar
         * entryPath:    W-INF/lib/groovy.jar
         * resourcePath: org/apache/groovy/json/FastStringService.class
         *
         * @param url
         * @throws IOException
         */
        public NestedJarURLConnection(URL url) throws IOException {
            super(url);
            parseSpecs(url);
        }

        private void parseSpecs(URL url) throws IOException {
            String spec = url.getPath();
            // Make sure contains !/, the jar in jar splitter
            int firstSeparatorIndex = spec.indexOf(JAR_URL_SEPARATOR);
            if (firstSeparatorIndex == -1) {
                throw new IOException("Invalid NestedJAR URL: " + url);
            }
            // Remove file: if contains
            this.rootJarPath = spec.substring(0, firstSeparatorIndex);
            if (rootJarPath.startsWith("file:")) {
                rootJarPath = rootJarPath.substring(5);
            }
            String remaining = spec.substring(firstSeparatorIndex + JAR_URL_SEPARATOR.length());
            // If there are another !/
            int secondSeparatorIndex = remaining.indexOf(JAR_URL_SEPARATOR);
            if (secondSeparatorIndex == -1) {
                this.entryPath = remaining;
                this.resourcePath = null;
            } else {
                this.entryPath = remaining.substring(0, secondSeparatorIndex);
                this.resourcePath = remaining.substring(secondSeparatorIndex + JAR_URL_SEPARATOR.length());
            }
        }

        @Override
        public void connect() throws IOException {
            if (connected) {
                return;
            }
            connected = true;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            JarFile rootJar = new JarFile(rootJarPath);
            JarEntry nestedJarEntry = rootJar.getJarEntry(entryPath);

            if (nestedJarEntry == null) {
                rootJar.close();
                throw new IOException("Cannot find entry: " + entryPath);
            }

            InputStream nestedJarStream = rootJar.getInputStream(nestedJarEntry);

            if (resourcePath == null) {
                return new CloseShieldInputStream(nestedJarStream, rootJar);
            } else {
                JarInputStream jis = new JarInputStream(nestedJarStream);
                JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    if (resourcePath.equals(entry.getName())) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = jis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        jis.close();
                        rootJar.close();
                        return new ByteArrayInputStream(baos.toByteArray());
                    }
                }
                jis.close();
                rootJar.close();
                throw new IOException("Cannot find resource in NestedJar: " + resourcePath);
            }
        }

        private static class CloseShieldInputStream extends InputStream {
            private final InputStream delegate;
            private final JarFile jarFile;

            public CloseShieldInputStream(InputStream delegate, JarFile jarFile) {
                this.delegate = delegate;
                this.jarFile = jarFile;
            }

            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public int read(byte[] b) throws IOException {
                return delegate.read(b);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return delegate.read(b, off, len);
            }

            @Override
            public long skip(long n) throws IOException {
                return delegate.skip(n);
            }

            @Override
            public int available() throws IOException {
                return delegate.available();
            }

            @Override
            public void close() throws IOException {
                try {
                    delegate.close();
                } finally {
                    jarFile.close();
                }
            }

            @Override
            public synchronized void mark(int readlimit) {
                delegate.mark(readlimit);
            }

            @Override
            public synchronized void reset() throws IOException {
                delegate.reset();
            }

            @Override
            public boolean markSupported() {
                return delegate.markSupported();
            }
        }
    }
}