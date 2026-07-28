package w.core.debug;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DebugHitStoreTest {
    @Test
    void truncatesOneHitToConfiguredByteLimit() throws Exception {
        RecordingListener listener = new RecordingListener(1);
        DebugHitStore store = new DebugHitStore(listener, 1024, 4096, 4);
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 10000; index++) {
            content.append('x');
        }

        Assertions.assertTrue(store.submit(rawHit(
                "hit-1", 1, new DebugHitStore.CapturedValue(
                        "arg0", null, String.class.getName(), content.toString(), null))));
        Assertions.assertTrue(listener.awaitStored());

        Map<String, Object> detail = store.get("hit-1");
        Assertions.assertTrue(Boolean.TRUE.equals(detail.get("truncated")));
        Assertions.assertTrue(JSON.toJSONBytes(detail).length <= 1024);
        Assertions.assertFalse(JSON.toJSONString(detail).contains("@identity"));
    }

    @Test
    void keepsCallStackIndependentFromValueRenderingBudget() throws Exception {
        RecordingListener listener = new RecordingListener(1);
        DebugHitStore store = new DebugHitStore(listener, 16384, 32768, 4);
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 10000; index++) {
            content.append('x');
        }

        Assertions.assertTrue(store.submit(rawHitWithStack(
                "hit-stack", content.toString())));
        Assertions.assertTrue(listener.awaitStored());

        Map<String, Object> detail = store.get("hit-stack");
        Assertions.assertEquals(12, ((List<?>) detail.get("callStack")).size());
    }

    @Test
    void evictsOldestDetailsWhenGlobalBudgetIsExceeded() throws Exception {
        RecordingListener listener = new RecordingListener(1);
        DebugHitStore store = new DebugHitStore(listener, 2048, 1, 4);

        Assertions.assertTrue(store.submit(rawHit("hit-1", 1, null)));
        Assertions.assertTrue(listener.awaitStored());
        Assertions.assertTrue(listener.awaitEvicted());
        Assertions.assertEquals(1, listener.evicted.get());
        Assertions.assertTrue(store.list("breakpoint").isEmpty());
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> store.get("hit-1"));
    }

    @Test
    void retainsExplicitCaptureErrorDetail() {
        RecordingListener listener = new RecordingListener(1);
        DebugHitStore store = new DebugHitStore(listener, 2048, 4096, 1);

        store.storeCaptureError(rawHit("hit-error", 1, null), "Capture queue is full");

        Map<String, Object> detail = store.get("hit-error");
        Assertions.assertEquals("Capture queue is full", detail.get("captureError"));
        Assertions.assertEquals(1, listener.captureErrors.get());
    }

    private DebugHitStore.RawHit rawHit(
            String hitId, int sequence, DebugHitStore.CapturedValue parameter) {
        DebugHitStore.Invocation invocation = new DebugHitStore.Invocation("breakpoint");
        invocation.complete(7, null, false);
        List<DebugHitStore.CapturedValue> parameters = parameter == null
                ? Collections.emptyList() : Collections.singletonList(parameter);
        return new DebugHitStore.RawHit(
                hitId, "breakpoint", sequence, System.currentTimeMillis(),
                1L, "test-thread", "RUNNABLE", false, 5,
                new StackTraceElement[]{
                    new StackTraceElement("example.Target", "call", "Target.java", 10)
                },
                parameters, parameters, new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), null, null, null, null, invocation);
    }

    private DebugHitStore.RawHit rawHitWithStack(String hitId, String content) {
        DebugHitStore.Invocation invocation = new DebugHitStore.Invocation("breakpoint");
        invocation.complete(7, null, false);
        List<StackTraceElement> stack = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            stack.add(new StackTraceElement(
                    "example.Target" + index, "call", "Target.java", index + 10));
        }
        DebugHitStore.CapturedValue parameter = new DebugHitStore.CapturedValue(
                "arg0", null, String.class.getName(), content, null);
        return new DebugHitStore.RawHit(
                hitId, "breakpoint", 1, System.currentTimeMillis(),
                1L, "test-thread", "RUNNABLE", false, 5,
                stack.toArray(new StackTraceElement[0]),
                Collections.singletonList(parameter), Collections.singletonList(parameter),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                null, null, null, null, invocation);
    }

    private static final class RecordingListener implements DebugHitStore.Listener {
        private final CountDownLatch stored;
        private final CountDownLatch eviction = new CountDownLatch(1);
        private final AtomicInteger captureErrors = new AtomicInteger();
        private final AtomicInteger evicted = new AtomicInteger();

        private RecordingListener(int storedCount) {
            stored = new CountDownLatch(storedCount);
        }

        @Override
        public boolean accepts(String breakpointId) {
            return true;
        }

        @Override
        public void onStored(DebugHitStore.RawHit raw, boolean captureError) {
            if (captureError) {
                captureErrors.incrementAndGet();
            }
            stored.countDown();
        }

        @Override
        public void onUpdated(DebugHitStore.RawHit raw) {
        }

        @Override
        public void onEvicted(DebugHitStore.RawHit raw) {
            evicted.incrementAndGet();
            eviction.countDown();
        }

        @Override
        public void onRemoved(DebugHitStore.RawHit raw) {
        }

        private boolean awaitStored() throws InterruptedException {
            return stored.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitEvicted() throws InterruptedException {
            return eviction.await(5, TimeUnit.SECONDS);
        }
    }
}
