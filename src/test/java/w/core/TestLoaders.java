package w.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class TestLoaders {
    private TestLoaders() {
    }

    public static byte[] classBytes(String resource) throws IOException {
        try (InputStream input = TestLoaders.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing test class: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public static final class NullParentLoader extends ClassLoader {
        private final String className;
        private final byte[] bytes;

        public NullParentLoader(byte[] bytes) {
            this("w.core.LoaderWatchTarget", bytes);
        }

        public NullParentLoader(String className, byte[] bytes) {
            super(null);
            this.className = className;
            this.bytes = bytes.clone();
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!className.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
