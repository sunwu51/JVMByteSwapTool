package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.model.WatchTransformer;
import w.web.message.DecompileMessage;
import w.web.message.WatchMessage;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Frank
 * @date 2024/6/24 23:06
 */
public class WatchTest {

    WatchTarget target = new WatchTarget();

    Swapper swapper = Swapper.getInstance();;

    @BeforeAll
    public static void setUp() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Global.instrumentation = instrumentation;
        Global.fillLoadedClasses();
        System.setProperty("maxHit", "3");
    }

    @BeforeEach
    public void reset() {
        Global.reset();
        Global.clearStash();
    }

    @Test
    public void test() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#voidMethodWithNoParams");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        WatchMessage msg2 = new WatchMessage();
        msg2.setPrintFormat(2);
        msg2.setSignature("w.core.WatchTarget#doubleMethodWithParams");
        Assertions.assertTrue(swapper.swap(msg2).isSuccess());
        WatchMessage msg3 = new WatchMessage();
        msg3.setSignature("w.core.WatchTarget#empty");
        Assertions.assertTrue(swapper.swap(msg3).isSuccess());

        WatchMessage msg4 = new WatchMessage();
        msg4.setSignature("w.core.WatchTarget#tryCatchTest");
        Assertions.assertTrue(swapper.swap(msg4).isSuccess());

        WatchMessage msg5 = new WatchMessage();
        msg5.setSignature("w.core.WatchTarget#readFile");
        Assertions.assertTrue(swapper.swap(msg5).isSuccess());
        target.voidMethodWithNoParams();
        target.doubleMethodWithParams(0.1324, 0.243543, 0.325432);
        target.empty();

        try {
            target.readFile("1.tdsafds");
        } catch (Exception e) {
            System.out.println(e.toString());
        }

        try {
            target.tryCatchTest("hello");
        } catch (Exception e) {
            System.out.println("success throw");
        }
        try {
            target.doubleMethodWithParams();
        } catch (Exception e) {
            System.out.println("success throw");
        }
    }

    @Test
    public void toStringTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#arrayReturn1");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        System.out.println(Arrays.toString(new WatchTarget().arrayReturn1()));

        new WatchTarget();
        WatchMessage msg2 = new WatchMessage();
        msg2.setSignature("w.core.WatchTarget#arrayReturn2");
        Assertions.assertTrue(swapper.swap(msg2).isSuccess());
        System.out.println(Arrays.toString(new WatchTarget().arrayReturn2()));

    }

    @Test
    public void returnArrTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#toString");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        System.out.println(new WatchTarget().toString());

        new WatchTarget();
        DecompileMessage msg2 = new DecompileMessage();
        msg2.setClassName("w.core.WatchTarget");
        Assertions.assertTrue(swapper.swap(msg2).isSuccess());

    }

    @Test
    public void runTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#run");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.run();
    }

    @Test
    public void watchShouldExposeDefaultAndCustomOgnlVariables() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#doubleMethodWithParams");
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("firstArg", "@w.Global@stash(\"watch-first\", #req[0][0])");
        variables.put("returnValue", "@w.Global@stash(\"watch-res\", #res)");
        msg.setVariables(variables);
        msg.setOgnl("#returnValue");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        target.doubleMethodWithParams(0.25);

        Assertions.assertEquals(0.25, (Double) Global.stash("watch-first"), 0.0001);
        Assertions.assertEquals(0.25, (Double) Global.stash("watch-res"), 0.0001);
    }

    @Test
    public void watchShouldExposeExceptionOgnlVariable() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#readFile");
        msg.setOgnl("@w.Global@stash(\"watch-exp\", #exp.getClass().getName())");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        try {
            WatchTarget.readFile("missing-watch-exp-file");
        } catch (Exception ignored) {
        }

        Assertions.assertEquals("java.nio.file.NoSuchFileException", Global.stash("watch-exp"));
    }
}
