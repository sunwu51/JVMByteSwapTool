package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.model.TraceTransformer;
import w.web.message.TraceMessage;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * @author Frank
 * @date 2024/6/26 22:46
 */
public class TraceTest {

    WatchTarget target = new WatchTarget();

    ChangeTarget changeTarget = new ChangeTarget();

    Swapper swapper = Swapper.getInstance();;

    @BeforeAll
    public static void setUp() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Global.instrumentation = instrumentation;
        Global.fillLoadedClasses();
    }

    @BeforeEach
    public void reset() {
        Global.reset();
    }

    @Test
    public void normalTest1() {
        TraceMessage msg = new TraceMessage();
        msg.setSignature("w.core.WatchTarget#callManyMethod");
        swapper.swap(msg);
        target.name = "test";
        target.callManyMethod();
        target.callManyMethod();
        Assertions.assertTrue(TraceTransformer.traceCtx.get().isEmpty());
    }

    @Test
    public void normalTest2() {
        TraceMessage msg = new TraceMessage();
        msg.setSignature("w.core.WatchTarget#callManyMethod");
        msg.setIgnoreZero(true);
        msg.setMinCost(28);
        swapper.swap(msg);
        target.name = "test";
        target.callManyMethod();
        target.callManyMethod();
        Assertions.assertTrue(TraceTransformer.traceCtx.get().isEmpty());
    }

    @Test
    public void recursiveTest1() {
        TraceMessage msg = new TraceMessage();
        msg.setSignature("w.core.WatchTarget#fib");
        swapper.swap(msg);
        target.fib(5);
    }


    @Test
    public void recursiveTest2() {
        TraceMessage msg = new TraceMessage();
        msg.setSignature("w.core.WatchTarget#ow1");
        swapper.swap(msg);
        target.ow1(1,1);
    }

    @Test
    public void includeNestedShouldCollectLambdaAndAnonymousTargets() {
        TraceMessage msg = new TraceMessage();
        msg.setSignature("w.core.ChangeTarget#lambdaTest");
        msg.setIncludeNested(true);
        TraceTransformer transformer = new TraceTransformer(msg);

        transformer.prepareNestedTargets(Collections.singleton(ChangeTarget.class));
        Map<String, Set<String>> targetMethods = transformer.getTargetMethods();

        Assertions.assertTrue(targetMethods.get("w.core.ChangeTarget").stream()
                .anyMatch(method -> method.startsWith("lambda$lambdaTest$")));
        Assertions.assertTrue(targetMethods.containsKey("w.core.ChangeTarget$1"));
        Assertions.assertTrue(targetMethods.get("w.core.ChangeTarget$1").contains("*"));
    }

    @Test
    public void includeNestedShouldTraceSynchronousLambdaRuntime() {
        TraceMessage msg = new TraceMessage();
        msg.setSignature("w.core.ChangeTarget#lambdaTest");
        msg.setIncludeNested(true);

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertEquals("lambdaTest", changeTarget.lambdaTest());
        Assertions.assertTrue(TraceTransformer.traceCtx.get().isEmpty());
    }
}
