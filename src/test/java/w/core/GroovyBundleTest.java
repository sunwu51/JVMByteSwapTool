package w.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.Global;
import w.util.SpringUtils;

class GroovyBundleTest {
    @Test
    void groovyClassLoaderShouldLoadAgentApisWithoutTargetLoaderVisibility() throws Exception {
        ClassLoader libraryLoader = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };

        try (GroovyBundle.WGroovyClassLoader loader =
                     new GroovyBundle.WGroovyClassLoader(libraryLoader, null)) {
            Assertions.assertSame(Global.class, loader.loadClass("w.Global"));
            Assertions.assertSame(SpringUtils.class, loader.loadClass("w.util.SpringUtils"));
            Assertions.assertSame(ExecBundle.class, loader.loadClass("w.core.ExecBundle"));
        }
    }
}
