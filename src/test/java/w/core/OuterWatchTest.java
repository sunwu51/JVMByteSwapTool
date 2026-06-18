package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.slf4j.MDC;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.model.OuterWatchTransformer;
import w.web.message.OuterWatchMessage;
import w.web.message.WatchMessage;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Frank
 * @date 2024/6/24 23:06
 */
public class OuterWatchTest {

    WatchTarget target = new WatchTarget();

    ChangeTarget changeTarget = new ChangeTarget();

    Swapper swapper = Swapper.getInstance();;

    @BeforeAll
    public static void setUp() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Global.instrumentation = instrumentation;
        Global.fillLoadedClasses();
        System.setProperty("maxHit", "10");
    }

    @BeforeEach
    public void reset() {
        Global.reset();
        OuterWatchTransformer.outerWatchCtx.remove();
    }

    @Test
    public void test() {
        OuterWatchMessage msg = new OuterWatchMessage();
        msg.setSignature("w.core.WatchTarget#subMethodCall");
        msg.setInnerSignature("w.core.WatchTarget#getAge");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());


        OuterWatchMessage msg2 = new OuterWatchMessage();
        msg2.setSignature("w.core.WatchTarget#subMethodCall");
        msg2.setInnerSignature("*#add");
        msg2.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg2).isSuccess());

        OuterWatchMessage msg3 = new OuterWatchMessage();
        msg3.setSignature("w.core.WatchTarget#subMethodCall");
        msg3.setInnerSignature("*#div");
        msg3.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg3).isSuccess());

         try {
             target.subMethodCall();
         } catch (Exception e) {
             System.out.println("\033[32m" + e.toString() + "\033[0m");
         }
    }

    @Test
    public void expTest() {
        OuterWatchMessage msg = new OuterWatchMessage();
        msg.setSignature("w.core.WatchTarget#subMethodCallExp");
        msg.setInnerSignature("*#readFile");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.doubleMethodWithParams(0.1);

    }

    @Test
    public void doubleReturnAndArrayParam2() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#doubleMethodWithParams");
        msg.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.doubleMethodWithParams(0.1);
    }

    @Test
    public void watchShouldLogOgnlValueFromThisRoot() {
        String logId = "watch-ognl-" + System.nanoTime();
        long since = System.currentTimeMillis();
        target.name = "watch-ognl-name";
        WatchMessage msg = new WatchMessage();
        msg.setId(logId);
        msg.setSignature("w.core.WatchTarget#doubleMethodWithParams");
        msg.setOgnl("name");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.doubleMethodWithParams(0.1);

        Assertions.assertTrue(Global.readLogs(logId, since, 20, 0).stream()
                .map(log -> String.valueOf(log.get("content")))
                .anyMatch(content -> content.contains("ognl:watch-ognl-name")));
    }

    @Test
    public void outerWatchShouldLogOgnlValueFromThisRoot() {
        String logId = "outer-watch-ognl-" + System.nanoTime();
        long since = System.currentTimeMillis();
        target.name = "outer-watch-ognl-name";
        OuterWatchMessage msg = new OuterWatchMessage();
        msg.setId(logId);
        msg.setSignature("w.core.WatchTarget#ow1");
        msg.setInnerSignature("*#add");
        msg.setOgnl("name");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.ow1(1, 2);

        Assertions.assertTrue(Global.readLogs(logId, since, 20, 0).stream()
                .map(log -> String.valueOf(log.get("content")))
                .anyMatch(content -> content.contains("ognl:outer-watch-ognl-name")));
    }

    @Test
    public void toStringTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#toString");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        System.out.println(new WatchTarget().toString());
    }

    @Test
    public void runTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#run");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.run();
    }

    @Test
    public void includeNestedShouldCollectLambdaTargets() {
        OuterWatchMessage msg = new OuterWatchMessage();
        msg.setSignature("w.core.ChangeTarget#outerWatchLambdaTest");
        msg.setInnerSignature("w.core.ChangeTarget#hello");
        msg.setIncludeNested(true);
        OuterWatchTransformer transformer = new OuterWatchTransformer(msg);

        transformer.prepareNestedTargets(Collections.singleton(ChangeTarget.class));
        Map<String, Set<String>> targetMethods = transformer.getTargetMethods();

        Assertions.assertTrue(targetMethods.get("w.core.ChangeTarget").stream()
                .anyMatch(method -> method.startsWith("lambda$outerWatchLambdaTest$")));
    }

    @Test
    public void includeNestedShouldWatchSynchronousLambdaInnerCall() {
        String logId = "outer-watch-nested-" + System.nanoTime();
        long since = System.currentTimeMillis();
        OuterWatchMessage msg = new OuterWatchMessage();
        msg.setId(logId);
        msg.setSignature("w.core.ChangeTarget#outerWatchLambdaTest");
        msg.setInnerSignature("w.core.ChangeTarget#hello");
        msg.setIncludeNested(true);

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        try {
            MDC.put("outerWatchLogId", logId);
            Assertions.assertEquals("outerWatchLambdaTest", changeTarget.outerWatchLambdaTest());
        } finally {
            MDC.clear();
        }
        List<Map<String, Object>> logs = Global.readLogs(logId, since, 20, 0);

        Assertions.assertTrue(logs.stream()
                .map(log -> String.valueOf(log.get("content")))
                .anyMatch(content -> content.contains("user will save")));
        Assertions.assertTrue(logs.stream()
                .map(log -> String.valueOf(log.get("content")))
                .anyMatch(content -> content.contains("outerWatchLogId=" + logId)));
        Assertions.assertTrue(OuterWatchTransformer.outerWatchCtx.get().isEmpty());
    }

    @Test
    public void duplicateOuterWatchShouldKeepEachLogId() {
        String firstLogId = "outer-watch-dup-first-" + System.nanoTime();
        String secondLogId = "outer-watch-dup-second-" + System.nanoTime();
        long since = System.currentTimeMillis();

        OuterWatchMessage first = new OuterWatchMessage();
        first.setId(firstLogId);
        first.setSignature("w.core.WatchTarget#subMethodCall");
        first.setInnerSignature("w.core.WatchTarget#getAge");
        Assertions.assertTrue(swapper.swap(first).isSuccess());

        OuterWatchMessage second = new OuterWatchMessage();
        second.setId(secondLogId);
        second.setSignature("w.core.WatchTarget#subMethodCall");
        second.setInnerSignature("w.core.WatchTarget#getAge");
        Assertions.assertTrue(swapper.swap(second).isSuccess());

        try {
            target.subMethodCall();
        } catch (Exception ignored) {
        }

        Assertions.assertFalse(Global.readLogs(firstLogId, since, 20, 0).isEmpty());
        Assertions.assertFalse(Global.readLogs(secondLogId, since, 20, 0).isEmpty());
    }
}
