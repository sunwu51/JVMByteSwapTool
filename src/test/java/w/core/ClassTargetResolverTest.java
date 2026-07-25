package w.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.Global;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ClassTargetResolverTest {
    @Test
    void duplicateNamesRequireHashAndExactHashSelectsTarget() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> first = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Class<?> second = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Instrumentation instrumentation = instrumentation(first, second);
        ClassTargetResolver resolver = new ClassTargetResolver(instrumentation);

        ClassTargetResolver.Resolution ambiguous =
                resolver.resolve("w.core.LoaderWatchTarget", null);
        Assertions.assertFalse(ambiguous.isSuccess());
        Assertions.assertEquals(2, ambiguous.candidateMaps().size());

        String hash = ClassTargetResolver.identityHash(first.getClassLoader());
        ClassTargetResolver.Resolution exact =
                resolver.resolve("w.core.LoaderWatchTarget", hash);
        Assertions.assertTrue(exact.isSuccess());
        Assertions.assertSame(first, exact.getTarget().getType());
    }

    @Test
    void bootstrapClassIsReported() {
        ClassTargetResolver resolver = new ClassTargetResolver(instrumentation(String.class));
        List<ClassTargetResolver.ClassTarget> targets = resolver.list("java.lang.String");

        Assertions.assertEquals(1, targets.size());
        Assertions.assertEquals("0", targets.get(0).getClassLoaderHash());
        Assertions.assertEquals("bootstrap", targets.get(0).toMap().get("classLoaderName"));
    }

    @Test
    void globalLoadedClassCacheExcludesBootstrapClasses() {
        Instrumentation previousInstrumentation = Global.instrumentation;
        Set<Class<?>> previous = Global.allLoadedClasses.remove(String.class.getName());
        Global.instrumentation = instrumentation(String.class, ClassTargetResolverTest.class);
        try {
            Global.fillLoadedClasses();

            Assertions.assertFalse(Global.allLoadedClasses.containsKey(String.class.getName()));
            Assertions.assertTrue(Global.allLoadedClasses
                    .getOrDefault(ClassTargetResolverTest.class.getName(), new HashSet<>())
                    .contains(ClassTargetResolverTest.class));
        } finally {
            Global.instrumentation = previousInstrumentation;
            Global.allLoadedClasses.remove(String.class.getName());
            if (previous != null) {
                Global.allLoadedClasses.put(String.class.getName(), previous);
            }
        }
    }

    private Instrumentation instrumentation(Class<?>... classes) {
        return (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("getAllLoadedClasses".equals(method.getName())) {
                        return classes;
                    }
                    if ("isModifiableClass".equals(method.getName())) {
                        return true;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
