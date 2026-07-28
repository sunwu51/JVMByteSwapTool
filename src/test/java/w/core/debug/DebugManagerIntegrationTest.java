package w.core.debug;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.BootstrapBridgeInstaller;
import w.core.ClassTargetResolver;
import w.core.TestLoaders;
import w.core.diagnostic.DiagnosticRuntime;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class DebugManagerIntegrationTest {
    private Instrumentation previousInstrumentation;
    private DebugManager manager;

    @BeforeEach
    void setUp() throws Exception {
        previousInstrumentation = Global.instrumentation;
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Global.instrumentation = instrumentation;
        OriginalBytecodeObserver.initialize(instrumentation);
        BootstrapBridgeInstaller.install(instrumentation);
        DiagnosticRuntime.initialize();
        manager = DebugManager.getInstance();
        manager.reset();
    }

    @AfterEach
    void tearDown() {
        Global.reset();
        Global.instrumentation = previousInstrumentation;
    }

    @Test
    void countsHitAndDisarmsAfterMaxHits() throws Exception {
        int displayLine = methodLine("calculate");

        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, displayLine, false, 60, 1);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        DebugTarget target = new DebugTarget();
        Assertions.assertEquals(7, target.calculate(3));
        Map<String, Object> terminal = awaitTerminal(breakpointId);
        Assertions.assertEquals("DISARMED", terminal.get("status"));
        Assertions.assertEquals("MAX_HITS", terminal.get("completionReason"));
        Assertions.assertEquals(Boolean.FALSE, terminal.get("transformerAttached"));
        Assertions.assertEquals(1, ((Number) terminal.get("hitCount")).intValue());
        Assertions.assertFalse(terminal.containsKey("capturedHits"));
        Assertions.assertFalse(terminal.containsKey("snapshotCount"));
    }

    @Test
    void capturesArgumentsInheritedFieldsStackAndPrimitiveReturn() throws Exception {
        int displayLine = methodLine("calculate");
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, displayLine, false, 60, 1);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        Assertions.assertEquals(7, new DebugTarget().calculate(3));
        Map<String, Object> terminal = awaitTerminal(breakpointId);
        List<Map<String, Object>> hits = awaitHits(breakpointId, 1);
        terminal = manager.getBreakpoint(breakpointId, 0);
        Map<String, Object> detail = manager.getHit(String.valueOf(hits.get(0).get("hitId")));

        Assertions.assertEquals(1, ((Number) terminal.get("retainedHitCount")).intValue());
        Assertions.assertEquals(0, ((Number) terminal.get("evictedHitCount")).intValue());
        Assertions.assertEquals(0, ((Number) terminal.get("captureErrorCount")).intValue());
        Assertions.assertEquals(hits.get(0).get("hitId"), terminal.get("latestHitId"));
        Assertions.assertTrue(hasNamedValue(detail, "parameters", "value"));
        Map<?, ?> breakpointSnapshot = (Map<?, ?>) detail.get("breakpointReturn");
        Assertions.assertEquals("SNAPSHOT", breakpointSnapshot.get("status"));
        Map<?, ?> snapshotValue = (Map<?, ?>) breakpointSnapshot.get("value");
        Assertions.assertEquals("OBJECT", snapshotValue.get("kind"));
        Assertions.assertEquals("breakpoint-line", snapshotValue.get("type"));
        Assertions.assertFalse(hasNamedValue(snapshotValue, "fields", "this"));
        Assertions.assertFalse(hasNamedValue(snapshotValue, "fields", "value"));
        Assertions.assertFalse(hasNamedValue(snapshotValue, "fields", "doubled"));
        Assertions.assertTrue(hasNamedValue(detail, "instanceFields", "instanceValue"));
        Assertions.assertTrue(hasNamedValue(detail, "instanceFields", "parentValue"));
        Assertions.assertTrue(hasNamedValue(detail, "staticFields", "staticValue"));
        Assertions.assertTrue(hasNamedValue(detail, "staticFields", "parentStaticValue"));
        Assertions.assertTrue(((List<?>) detail.get("callStack")).size() >= 4,
                () -> "captured call stack should retain application and caller frames: "
                        + detail.get("callStack"));
        Map<?, ?> returnValue = (Map<?, ?>) detail.get("return");
        Assertions.assertEquals("RETURNED", returnValue.get("status"));
        Assertions.assertEquals(7,
                ((Number) ((Map<?, ?>) returnValue.get("value")).get("value")).intValue());
        Assertions.assertFalse(Global.toJson(detail).contains("@identity"));
    }

    @Test
    void breakpointSnapshotContainsLocalsButNotReceiverOrParameters() throws Exception {
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("sum") + 3, false, 60, 1);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        Assertions.assertEquals(1, new DebugTarget().sum(1));
        Map<String, Object> detail = manager.getHit(
                String.valueOf(awaitHits(breakpointId, 1).get(0).get("hitId")));
        Map<?, ?> snapshot = (Map<?, ?>) ((Map<?, ?>) detail.get("breakpointReturn"))
                .get("value");

        Assertions.assertTrue(hasNamedValue(snapshot, "fields", "result"),
                Global.toJson(detail));
        Assertions.assertTrue(hasNamedValue(snapshot, "fields", "index"),
                Global.toJson(detail));
        Assertions.assertFalse(hasNamedValue(snapshot, "fields", "this"));
        Assertions.assertFalse(hasNamedValue(snapshot, "fields", "count"));
        Assertions.assertTrue(hasNamedValue(detail, "parameters", "count"));
    }

    @Test
    void capturesBreakpointCallParametersSeparatelyFromOuterMethod() throws Exception {
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("callAndStore"),
                false, 60, 1);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        Assertions.assertEquals(5, new DebugTarget().callAndStore(3));
        Map<String, Object> detail = manager.getHit(
                String.valueOf(awaitHits(breakpointId, 1).get(0).get("hitId")));
        Map<?, ?> breakpointReturn = (Map<?, ?>) detail.get("breakpointReturn");

        Assertions.assertTrue(hasNamedValue(detail, "parameters", "value"),
                Global.toJson(detail));
        Assertions.assertTrue(hasNamedValue(detail, "parameters", "parameter2"),
                Global.toJson(detail));
        Assertions.assertFalse(hasNamedValue(detail, "parameters", "arg0"));
        Assertions.assertTrue(hasNamedValue(detail, "methodParameters", "value"));
        Assertions.assertEquals("RETURNED", breakpointReturn.get("status"));
        Assertions.assertEquals(5,
                ((Number) ((Map<?, ?>) breakpointReturn.get("value")).get("value"))
                        .intValue());
        Assertions.assertEquals("RETURNED",
                ((Map<?, ?>) detail.get("return")).get("status"));
    }

    @Test
    void capturesOneHitForOneBreakpointLineExecutionWithMultipleCalls() throws Exception {
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("chainedCalls"),
                false, 60, 5);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        Assertions.assertEquals(9, new DebugTarget().chainedCalls(3));
        List<Map<String, Object>> hits = awaitHits(breakpointId, 1);

        Assertions.assertEquals(1, hits.size());
        Assertions.assertEquals(1,
                ((Number) manager.getBreakpoint(breakpointId, 0).get("hitCount"))
                        .intValue());
    }

    @Test
    void capturesBreakpointCallThrowWithoutChangingOuterThrow() throws Exception {
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("callFailure"),
                false, 60, 1);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        Assertions.assertThrows(
                IllegalStateException.class, () -> new DebugTarget().callFailure(4));
        Map<String, Object> detail = manager.getHit(
                String.valueOf(awaitHits(breakpointId, 1).get(0).get("hitId")));
        Map<?, ?> breakpointThrow = (Map<?, ?>) detail.get("breakpointReturn");
        Map<?, ?> methodThrow = (Map<?, ?>) detail.get("return");

        Assertions.assertTrue(hasNamedValue(detail, "parameters", "value"));
        Assertions.assertTrue(hasNamedValue(detail, "methodParameters", "value"));
        Assertions.assertEquals("THREW", breakpointThrow.get("status"));
        Assertions.assertEquals(IllegalStateException.class.getName(),
                ((Map<?, ?>) breakpointThrow.get("exception")).get("type"));
        Assertions.assertEquals("THREW", methodThrow.get("status"));
    }

    @Test
    void returnLineUsesOuterResultAndConstructorParameters() throws Exception {
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("buildResult"),
                false, 60, 1);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        DebugTarget.Result result = new DebugTarget().buildResult(7);
        Assertions.assertEquals(7, result.value);
        Map<String, Object> detail = manager.getHit(
                String.valueOf(awaitHits(breakpointId, 1).get(0).get("hitId")));
        Map<?, ?> breakpointReturn = (Map<?, ?>) detail.get("breakpointReturn");

        Assertions.assertTrue(hasNamedValue(detail, "parameters", "value"));
        Assertions.assertTrue(hasNamedValue(detail, "parameters", "parameter2"));
        Assertions.assertTrue(hasNamedValue(detail, "methodParameters", "value"));
        Assertions.assertEquals("RETURNED", breakpointReturn.get("status"));
        Assertions.assertEquals(DebugTarget.Result.class.getName(),
                ((Map<?, ?>) breakpointReturn.get("value")).get("type"));
    }

    @Test
    void capturesVoidAndThrownExits() throws Exception {
        Map<String, Object> voidBreakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("consume"), false, 60, 1);
        String voidId = String.valueOf(voidBreakpoint.get("breakpointId"));
        new DebugTarget().consume(9);
        awaitTerminal(voidId);
        Map<String, Object> voidDetail = manager.getHit(
                String.valueOf(awaitHits(voidId, 1).get(0).get("hitId")));
        Assertions.assertEquals("VOID", ((Map<?, ?>) voidDetail.get("return")).get("status"));

        Map<String, Object> throwBreakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("fail"), false, 60, 1);
        String throwId = String.valueOf(throwBreakpoint.get("breakpointId"));
        Assertions.assertThrows(IllegalStateException.class, () -> new DebugTarget().fail(4));
        awaitTerminal(throwId);
        Map<String, Object> throwDetail = manager.getHit(
                String.valueOf(awaitHits(throwId, 1).get(0).get("hitId")));
        Map<?, ?> thrown = (Map<?, ?>) throwDetail.get("return");
        Assertions.assertEquals("THREW", thrown.get("status"));
        Assertions.assertEquals(IllegalStateException.class.getName(),
                ((Map<?, ?>) thrown.get("exception")).get("type"));
    }

    @Test
    void capturesReturnAndThrowAtBreakpointLineSeparatelyFromMethodReturn() throws Exception {
        Map<String, Object> returnBreakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("echo"), false, 60, 1);
        String returnId = String.valueOf(returnBreakpoint.get("breakpointId"));
        Assertions.assertEquals("hello", new DebugTarget().echo("hello"));
        Map<String, Object> returnDetail = manager.getHit(
                String.valueOf(awaitHits(returnId, 1).get(0).get("hitId")));
        Map<?, ?> breakpointReturn = (Map<?, ?>) returnDetail.get("breakpointReturn");
        Assertions.assertEquals("RETURNED", breakpointReturn.get("status"));
        Assertions.assertEquals("hello", ((Map<?, ?>) breakpointReturn.get("value")).get("value"));

        Map<String, Object> throwBreakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, methodLine("fail"), false, 60, 1);
        String throwId = String.valueOf(throwBreakpoint.get("breakpointId"));
        Assertions.assertThrows(IllegalStateException.class, () -> new DebugTarget().fail(4));
        Map<String, Object> throwDetail = manager.getHit(
                String.valueOf(awaitHits(throwId, 1).get(0).get("hitId")));
        Map<?, ?> breakpointThrow = (Map<?, ?>) throwDetail.get("breakpointReturn");
        Assertions.assertEquals("THREW", breakpointThrow.get("status"));
        Assertions.assertEquals(IllegalStateException.class.getName(),
                ((Map<?, ?>) breakpointThrow.get("exception")).get("type"));
    }

    @Test
    void multipleHitsInOneInvocationShareFinalReturn() throws Exception {
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, lastMethodLine("sum"), false, 60, 3);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        Assertions.assertEquals(3, new DebugTarget().sum(3));
        awaitTerminal(breakpointId);
        List<Map<String, Object>> hits = awaitHits(breakpointId, 3);

        Assertions.assertEquals(3, hits.size());
        for (Map<String, Object> hit : hits) {
            Map<String, Object> detail = manager.getHit(String.valueOf(hit.get("hitId")));
            Map<?, ?> returned = (Map<?, ?>) detail.get("return");
            Assertions.assertEquals("RETURNED", returned.get("status"));
            Assertions.assertEquals(3,
                    ((Number) ((Map<?, ?>) returned.get("value")).get("value")).intValue());
        }
    }

    @Test
    void concurrentHitsNeverExceedMaximum() throws Exception {
        int displayLine = methodLine("calculate");
        Map<String, Object> breakpoint = manager.addBreakpoint(
                DebugTarget.class.getName(), null, displayLine, false, 60, 25);
        String breakpointId = String.valueOf(breakpoint.get("breakpointId"));

        ExecutorService callers = Executors.newFixedThreadPool(8);
        for (int index = 0; index < 200; index++) {
            callers.execute(() -> manager.hit(breakpointId));
        }
        callers.shutdown();
        Assertions.assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));

        Map<String, Object> terminal = awaitTerminal(breakpointId);
        Assertions.assertEquals("MAX_HITS", terminal.get("completionReason"));
        Assertions.assertEquals(25, ((Number) terminal.get("hitCount")).intValue());
        Assertions.assertEquals(0, ((Number) terminal.get("remainingHits")).intValue());
    }

    @Test
    void clearHistoryRemovesTerminalRecordsAndRetainsActiveOnes() throws Exception {
        int firstLine = methodLine("calculate");
        int secondLine = methodLine("echo");
        Map<String, Object> completed = manager.addBreakpoint(
                DebugTarget.class.getName(), null, firstLine, false, 60, 10);
        manager.removeBreakpoint(String.valueOf(completed.get("breakpointId")));
        awaitTerminal(String.valueOf(completed.get("breakpointId")));

        Map<String, Object> active = manager.addBreakpoint(
                DebugTarget.class.getName(), null, secondLine, false, 60, 10);
        Map<String, Object> result = manager.clearHistory();

        Assertions.assertEquals(1, ((Number) result.get("removedCount")).intValue());
        Assertions.assertEquals(0,
                ((Number) result.get("retainedAttachedCount")).intValue());
        Assertions.assertEquals(active.get("breakpointId"),
                manager.listBreakpoints().get(0).get("breakpointId"));
    }

    @Test
    void duplicateBreakpointReturnsExistingId() throws Exception {
        int displayLine = methodLine("calculate");

        Map<String, Object> first = manager.addBreakpoint(
                DebugTarget.class.getName(), null, displayLine, false, 60, 10);
        Map<String, Object> second = manager.addBreakpoint(
                DebugTarget.class.getName(), null, displayLine, false, 60, 10);

        Assertions.assertEquals("CREATED", first.get("outcome"));
        Assertions.assertEquals("ALREADY_EXISTS", second.get("outcome"));
        Assertions.assertEquals(first.get("breakpointId"), second.get("breakpointId"));
        manager.removeBreakpoint(String.valueOf(first.get("breakpointId")));
        Assertions.assertEquals(
                "DISARMED", awaitTerminal(String.valueOf(first.get("breakpointId"))).get("status"));
    }

    @Test
    void sharedOuterAndLambdaLineIsNotSelectable() throws Exception {
        Map<String, Object> inspection =
                manager.inspectClass(DebugAmbiguousTarget.class.getName(), null);
        List<?> ambiguous = (List<?>) inspection.get("ambiguousDisplayLines");
        Assertions.assertFalse(ambiguous.isEmpty());
        List<?> executable = (List<?>) inspection.get("executableDisplayLines");
        Assertions.assertFalse(executable.contains(ambiguous.get(0)));

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> manager.addBreakpoint(DebugAmbiguousTarget.class.getName(), null,
                        ((Number) ambiguous.get(0)).intValue(), false, 60, 1));
        Assertions.assertTrue(error.getMessage().contains("more than one"));
    }

    @Test
    void inspectsPreloadedClassWhenLoaderDoesNotExposeClassResource() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/LoaderWatchTarget.class");
        Class<?> target =
                new TestLoaders.NullParentLoader(bytes).loadClass("w.core.LoaderWatchTarget");
        Map<Object, byte[]> initialCache = bytecodeCache("INITIAL_BYTES");
        Map<Object, byte[]> recoveredCache = bytecodeCache("RECOVERED_BYTES");
        Map<Object, byte[]> savedInitial = new HashMap<>(initialCache);
        Map<Object, byte[]> savedRecovered = new HashMap<>(recoveredCache);
        try {
            initialCache.clear();
            recoveredCache.clear();

            Map<String, Object> inspection = manager.inspectClass(
                    target.getName(),
                    ClassTargetResolver.identityHash(target.getClassLoader()));

            Assertions.assertEquals(target.getName(), inspection.get("className"));
            Assertions.assertFalse(
                    ((List<?>) inspection.get("executableDisplayLines")).isEmpty());
        } finally {
            initialCache.clear();
            initialCache.putAll(savedInitial);
            recoveredCache.clear();
            recoveredCache.putAll(savedRecovered);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Object, byte[]> bytecodeCache(String fieldName) throws Exception {
        Field field = OriginalBytecodeObserver.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<Object, byte[]>) field.get(null);
    }

    private Map<String, Object> awaitTerminal(String breakpointId) {
        long deadline = System.currentTimeMillis() + 5000;
        Map<String, Object> current = manager.getBreakpoint(breakpointId, 1000);
        while (("ARMED".equals(current.get("status"))
                || "DISARMING".equals(current.get("status")))
                && System.currentTimeMillis() < deadline) {
            current = manager.getBreakpoint(breakpointId, 1000);
        }
        return current;
    }

    private List<Map<String, Object>> awaitHits(String breakpointId, int expected) {
        long deadline = System.currentTimeMillis() + 5000;
        List<Map<String, Object>> hits = manager.listHits(breakpointId);
        while (hits.size() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            hits = manager.listHits(breakpointId);
        }
        Assertions.assertEquals(expected, hits.size());
        return hits;
    }

    private int methodLine(String methodName) throws Exception {
        return methodLines(methodName).get(0);
    }

    private int lastMethodLine(String methodName) throws Exception {
        List<Integer> lines = methodLines(methodName);
        return lines.get(lines.size() - 2);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> methodLines(String methodName) throws Exception {
        Map<String, Object> inspection =
                manager.inspectClass(DebugTarget.class.getName(), null);
        for (Map<String, Object> method
                : (List<Map<String, Object>>) inspection.get("methods")) {
            if (methodName.equals(method.get("name"))) {
                return (List<Integer>) method.get("executableDisplayLines");
            }
        }
        throw new AssertionError("method not found: " + methodName);
    }

    private boolean hasNamedValue(Map<?, ?> detail,
                                  String section, String name) {
        Object values = detail.get(section);
        if (!(values instanceof List<?>)) {
            return false;
        }
        for (Object value : (List<?>) values) {
            if (!(value instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> item = (Map<?, ?>) value;
            if (name.equals(item.get("name"))) {
                return true;
            }
        }
        return false;
    }
}
