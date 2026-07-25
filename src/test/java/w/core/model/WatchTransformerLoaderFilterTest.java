package w.core.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.core.TestLoaders;
import w.web.message.WatchMessage;

class WatchTransformerLoaderFilterTest {
    @Test
    void callbackIgnoresSameNamedClassFromOtherLoader() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        TestLoaders.NullParentLoader selected = new TestLoaders.NullParentLoader(bytes);
        TestLoaders.NullParentLoader other = new TestLoaders.NullParentLoader(bytes);
        Class<?> selectedClass = selected.loadClass("w.core.LoaderWatchTarget");
        Class<?> otherClass = other.loadClass("w.core.LoaderWatchTarget");
        WatchMessage message = new WatchMessage();
        message.setSignature("w.core.LoaderWatchTarget#echo");
        WatchTransformer transformer = new WatchTransformer(message);
        transformer.targetClassLoader(selected);

        byte[] ignored = transformer.transform(
                other,
                "w/core/LoaderWatchTarget",
                otherClass,
                null,
                bytes);
        Assertions.assertNull(ignored);

        transformer.beginApply(selectedClass);
        byte[] transformed = transformer.transform(
                selected,
                "w/core/LoaderWatchTarget",
                selectedClass,
                null,
                bytes);
        Assertions.assertNotNull(transformed);
        Assertions.assertTrue(transformer.getApplyResult(selectedClass).isSuccess());
    }

    @Test
    void frameComputationSupportsBootstrapTargetLoader() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        WatchMessage message = new WatchMessage();
        message.setSignature("w.core.LoaderWatchTarget#mergeTypes");
        WatchTransformer transformer = new WatchTransformer(message);
        transformer.targetClassLoader(null);

        Assertions.assertDoesNotThrow(() -> transformer.transform(bytes));
    }
}
