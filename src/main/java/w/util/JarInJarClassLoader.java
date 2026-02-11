package w.util;

import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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


    private final Map<String, byte[]> classDataCache = new ConcurrentHashMap<>();

    private final Map<String, List<URL>> resourceUrlCache = new ConcurrentHashMap<>();

    public JarInJarClassLoader(URL jarUrl, String entryPrefix, ClassLoader parent) throws IOException {
        super(new URL[0], parent);
        this.rootJarUrl = jarUrl;
        this.rootJarFile = new JarFile(jarUrl.getFile());
        // Search all jars in rootJar!/entryPrefix/xx.jar, add to nestedJars
        // And create nestedJarUrl add to the URLClassLoader.
        loadAndPreprocessJars(entryPrefix);
    }
    private void loadAndPreprocessJars(String entryPrefix) throws IOException {
        // search all jars
        Enumeration<JarEntry> entries = rootJarFile.entries();
        List<JarEntry> jarEntries = new ArrayList<>();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().startsWith(entryPrefix) && entry.getName().endsWith(".jar")) {
                jarEntries.add(entry);
            }
        }
        // parallel process each jar
        jarEntries.parallelStream().forEach(entry -> {
            try {
                preprocessJar(entry);
            } catch (IOException e) {
                throw new RuntimeException("Error preprocessing JAR: " + entry.getName() + ": " + e.getMessage());
            }
        });
    }
    private void preprocessJar(JarEntry jarEntry) throws IOException {
        NestedJarEntry nestedJar = new NestedJarEntry(rootJarFile, jarEntry);
        URL nestedJarUrl = new URL(PROTOCOL, "", -1,
                rootJarUrl + "!/" + jarEntry.getName(), new Handler());
        addURL(nestedJarUrl);

        // read all jars in rootJar, and cache the resource to mem
        byte[] jarContent = nestedJar.getContent();
        try (InputStream is = new ByteArrayInputStream(jarContent);
             JarInputStream jis = new JarInputStream(is)) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(
                            entry.getSize() > 0 ? (int)entry.getSize() : 4096);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = jis.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    byte[] entryData = baos.toByteArray();
                    if (name.endsWith(".class")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        synchronized (classDataCache) {
                            classDataCache.putIfAbsent(className, entryData);
                        }
                    }
                    try {
                        URL resourceUrl = new URL(PROTOCOL, "", -1,
                                rootJarUrl.toString() + "!/" + jarEntry.getName() + "!/" + name,
                                new Handler(name, entryData));

                        synchronized (resourceUrlCache) {
                            resourceUrlCache
                                    .computeIfAbsent(name, k -> new ArrayList<>())
                                    .add(resourceUrl);
                        }
                    } catch (MalformedURLException e) {
                    }
                }
            }
        }
    }


    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] classData = classDataCache.get(name);
        if (classData != null) {
            return defineClass(name, classData, 0, classData.length,
                    new CodeSource(rootJarUrl, (Certificate[]) null));
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public URL findResource(String name) {
        List<URL> urls = resourceUrlCache.get(name);
        if (urls != null && !urls.isEmpty()) {
            return urls.get(0);
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        List<URL> resources = new ArrayList<>();

        List<URL> cachedUrls = resourceUrlCache.get(name);
        if (cachedUrls != null) {
            resources.addAll(cachedUrls);
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
            classDataCache.clear();
            resourceUrlCache.clear();
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

        public byte[] getContent() throws IOException {
            if (cachedContent == null) {
                synchronized (this) {
                    if (cachedContent == null) {
                        try (InputStream is = rootJarFile.getInputStream(jarEntry)) {
                            int size = (int) jarEntry.getSize();
                            if (size > 0) {
                                cachedContent = new byte[size];
                                int totalRead = 0, bytesRead;
                                while (totalRead < size &&
                                        (bytesRead = is.read(cachedContent, totalRead, size - totalRead)) != -1) {
                                    totalRead += bytesRead;
                                }
                            } else {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                                byte[] buffer = new byte[16384];
                                int bytesRead;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    baos.write(buffer, 0, bytesRead);
                                }
                                cachedContent = baos.toByteArray();
                            }
                        }
                    }
                }
            }
            return cachedContent;
        }
    }
}
class Handler extends URLStreamHandler {
    private byte[] resourceData;
    
    public Handler() {
    }

    public Handler(String resourceName, byte[] resourceData) {
        this.resourceData = resourceData;
    }
    private static class CachedURLConnection extends URLConnection {
        private final byte[] resourceData;

        public CachedURLConnection(URL url, byte[] resourceData) {
            super(url);
            this.resourceData = resourceData;
        }

        @Override
        public void connect() throws IOException {
            connected = true;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(resourceData);
        }

        @Override
        public int getContentLength() {
            return resourceData.length;
        }
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        if (resourceData != null) {
            return new CachedURLConnection(url, resourceData);
        } else {
            return new NestedJarURLConnection(url);
        }
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


    public static void main(String[] args) throws Exception {
        String rootJar = "C:/Users/sunwu/Desktop/sw/JVMByteSwapTool/target/swapper-0.0.1-SNAPSHOT.jar";


        JarInJarClassLoader cl = new JarInJarClassLoader(new URL("file:/" + rootJar), "W-INF/lib", ClassLoader.getSystemClassLoader().getParent());

        test1(cl);
        test1(ClassLoader.getSystemClassLoader());

    }
    private static void test1(ClassLoader cl) throws Exception {
        Thread.currentThread().setContextClassLoader(cl);
        Class<?> engineC = cl.loadClass(GroovyScriptEngineImpl.class.getName());
        long start = System.currentTimeMillis();
        engineC.getMethod("eval", String.class).invoke(
                engineC.getDeclaredConstructor().newInstance(), "System.out.println('hello world')"
        );
        System.out.println("cost" + (System.currentTimeMillis() - start));
    }
}