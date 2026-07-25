package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.model.SwapResult;
import w.core.watch.WatchRuntime;
import w.util.RequestUtils;
import w.web.message.WatchMessage;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

class WatchClassLoaderIntegrationTest {
    private static Instrumentation instrumentation;

    @BeforeAll
    static void installAgent() {
        instrumentation = ByteBuddyAgent.install();
        Global.instrumentation = instrumentation;
        try {
            BootstrapBridgeInstaller.install(instrumentation);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.setProperty("maxHit", "100");
    }

    @AfterEach
    void reset() {
        Global.reset();
    }

    @Test
    void extractedBootstrapJarContainsOnlyBridgeApi() throws Exception {
        try (JarFile jar = new JarFile(BootstrapBridgeInstaller.getBridgeJar())) {
            Assertions.assertNotNull(jar.getEntry("w/bridge/WatchBridge.class"));
            Assertions.assertNotNull(jar.getEntry("w/bridge/WatchListener.class"));
            Assertions.assertEquals(2, jar.size());
        }
        Assertions.assertNull(Class.forName("w.bridge.WatchBridge", false, null).getClassLoader());
    }

    @Test
    void nullParentDuplicateClassRequiresHashAndOnlyTransformsSelectedLoader() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> first = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Class<?> second = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Object firstTarget = first.newInstance();
        Object secondTarget = second.newInstance();
        Global.fillLoadedClasses();

        WatchMessage ambiguous = message("loader-ambiguous");
        SwapResult ambiguousResult = Swapper.getInstance().swap(ambiguous);
        Assertions.assertFalse(ambiguousResult.isSuccess());
        Assertions.assertTrue(ambiguousResult.getMessage().contains("classLoaderHash is required"));
        Assertions.assertTrue(ambiguousResult.getCandidates().size() >= 2);

        String traceId = "loader-exact-" + System.nanoTime();
        WatchMessage exact = message(traceId);
        exact.setClassLoaderHash(ClassTargetResolver.identityHash(first.getClassLoader()));
        SwapResult installed = Swapper.getInstance().swap(exact);
        Assertions.assertTrue(installed.isSuccess(), installed.getMessage());
        Assertions.assertEquals(1, installed.getApplyResults().size());
        Assertions.assertTrue(installed.getApplyResults().get(0).getClassLoader()
                .contains(first.getClassLoader().toString()));

        Method secondEcho = second.getMethod("echo", String.class);
        Assertions.assertEquals("echo:second", secondEcho.invoke(secondTarget, "second"));
        Assertions.assertTrue(Global.readLogs(traceId, 0, 100, 0).isEmpty());

        Method firstEcho = first.getMethod("echo", String.class);
        Assertions.assertEquals("echo:first", firstEcho.invoke(firstTarget, "first"));
        List<Map<String, Object>> logs = Global.readLogs(traceId, 0, 100, 0);
        Assertions.assertEquals(1, logs.size());
        Assertions.assertTrue(String.valueOf(logs.get(0).get("content")).contains("req:[first]"));
    }

    @Test
    void originalThrowableIdentitySurvivesListenerAndOgnlFailures() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> targetClass = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Object target = targetClass.newInstance();
        WatchMessage message = message("loader-throw-" + System.nanoTime());
        message.setSignature("w.core.LoaderWatchTarget#fail");
        message.setClassLoaderHash(ClassTargetResolver.identityHash(targetClass.getClassLoader()));
        message.setOgnl("missing.property.call()");

        Assertions.assertTrue(Swapper.getInstance().swap(message).isSuccess());
        Method fail = targetClass.getMethod("fail", String.class);
        InvocationTargetException invocation = Assertions.assertThrows(
                InvocationTargetException.class, () -> fail.invoke(target, "original"));
        Assertions.assertEquals(IllegalStateException.class, invocation.getCause().getClass());
        Assertions.assertEquals("original", invocation.getCause().getMessage());
    }

    @Test
    void jsonFailureAndNestedCallsPreserveResultAndClearTraceContext() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> targetClass = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Object target = targetClass.newInstance();
        String hash = ClassTargetResolver.identityHash(targetClass.getClassLoader());

        WatchMessage json = message("loader-json-" + System.nanoTime());
        json.setSignature("w.core.LoaderWatchTarget#badJson");
        json.setClassLoaderHash(hash);
        json.setPrintFormat(2);
        Assertions.assertTrue(Swapper.getInstance().swap(json).isSuccess());
        Assertions.assertSame(target, targetClass.getMethod("badJson").invoke(target));
        Global.reset();

        WatchMessage nested = message("loader-nested-" + System.nanoTime());
        nested.setSignature("w.core.LoaderWatchTarget#recurse");
        nested.setClassLoaderHash(hash);
        Assertions.assertTrue(Swapper.getInstance().swap(nested).isSuccess());
        Assertions.assertEquals(4, targetClass.getMethod("recurse", int.class).invoke(target, 4));
        Assertions.assertNull(RequestUtils.getCurTraceId());
    }

    @Test
    void concurrentResetKeepsActiveTokenSnapshotAndCleansContext() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> targetClass = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Object target = targetClass.newInstance();
        WatchMessage message = message("loader-reset-" + System.nanoTime());
        message.setSignature("w.core.LoaderWatchTarget#waitOn");
        message.setClassLoaderHash(ClassTargetResolver.identityHash(targetClass.getClassLoader()));
        message.setVariables(java.util.Collections.singletonMap("captured", "#req[0].getCount()"));
        message.setOgnl("#captured");
        SwapResult installed = Swapper.getInstance().swap(message);
        Assertions.assertTrue(installed.isSuccess());
        UUID transformerId = installed.getTransformerId();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<String> remainingTrace = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(targetClass.getMethod("waitOn", CountDownLatch.class, CountDownLatch.class)
                        .invoke(target, entered, release));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                remainingTrace.set(RequestUtils.getCurTraceId());
            }
        });
        worker.start();
        Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
        Global.reset();
        Assertions.assertFalse(WatchRuntime.isRegistered(transformerId.toString()));
        release.countDown();
        worker.join(5000);

        Assertions.assertFalse(worker.isAlive());
        Assertions.assertNull(failure.get());
        Assertions.assertEquals("released", result.get());
        Assertions.assertNull(remainingTrace.get());
    }

    @Test
    void maxHitAutomaticallyUnregistersWatch() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> targetClass = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Object target = targetClass.newInstance();
        WatchMessage message = message("loader-max-hit-" + System.nanoTime());
        message.setClassLoaderHash(ClassTargetResolver.identityHash(targetClass.getClassLoader()));
        SwapResult installed = Swapper.getInstance().swap(message);
        Assertions.assertTrue(installed.isSuccess());
        System.setProperty("maxHit", "1");
        try {
            Assertions.assertEquals("echo:one",
                    targetClass.getMethod("echo", String.class).invoke(target, "one"));
            Assertions.assertFalse(WatchRuntime.isRegistered(installed.getTransformerId().toString()));
            Assertions.assertNull(RequestUtils.getCurTraceId());
        } finally {
            System.setProperty("maxHit", "100");
        }
    }

    @Test
    void deleteRetransformsAppliedClassWhenLoadedClassCacheIsStale() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> targetClass = new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        WatchMessage message = message("loader-delete-" + System.nanoTime());
        message.setClassLoaderHash(ClassTargetResolver.identityHash(targetClass.getClassLoader()));
        SwapResult installed = Swapper.getInstance().swap(message);
        Assertions.assertTrue(installed.isSuccess());

        java.util.Set<Class<?>> cached = Global.allLoadedClasses.get(targetClass.getName());
        if (cached != null) {
            cached.remove(targetClass);
        }
        AtomicInteger retransforms = new AtomicInteger();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (classBeingRedefined == targetClass) {
                    retransforms.incrementAndGet();
                }
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            Global.deleteTransformer(installed.getTransformerId());
            Assertions.assertTrue(retransforms.get() > 0);
        } finally {
            instrumentation.removeTransformer(observer);
        }
    }

    private WatchMessage message(String traceId) {
        WatchMessage message = new WatchMessage();
        message.setId(traceId);
        message.setSignature("w.core.LoaderWatchTarget#echo");
        return message;
    }
}
