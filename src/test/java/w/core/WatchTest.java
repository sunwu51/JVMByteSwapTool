package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.model.WatchTransformer;
import w.web.message.WatchMessage;

import java.lang.instrument.Instrumentation;

/**
 * @author Frank
 * @date 2024/6/24 23:06
 */
public class WatchTest {

    Target target = new Target();

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
    }

    @Test
    public void voidTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.Target#voidMethodWithNoParams");
        Assertions.assertTrue(swapper.swap(msg));
        WatchMessage msg2 = new WatchMessage();
        msg2.setPrintFormat(2);
        msg2.setSignature("w.core.Target#doubleMethodWithParams");
        Assertions.assertTrue(swapper.swap(msg2));
        WatchMessage msg3 = new WatchMessage();
        msg3.setSignature("w.core.Target#empty");
        Assertions.assertTrue(swapper.swap(msg3));

        WatchMessage msg4 = new WatchMessage();
        msg4.setSignature("w.core.Target#tryCatchTest");
        Assertions.assertTrue(swapper.swap(msg4));

        WatchMessage msg5 = new WatchMessage();
        msg5.setSignature("w.core.Target#readFile");
        Assertions.assertTrue(swapper.swap(msg5));
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
    public void doubleReturnAndArrayParam() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.Target#doubleMethodWithParams");
        Assertions.assertTrue(swapper.swap(msg));
        target.doubleMethodWithParams();
    }

    @Test
    public void doubleReturnAndArrayParam2() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.Target#doubleMethodWithParams");
        msg.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg));
        target.doubleMethodWithParams();
    }
    @Test
    public void toStringTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.Target#toString");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(new Target().toString());
    }

    @Test
    public void runTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.Target#run");
        Assertions.assertTrue(swapper.swap(msg));
        target.run();
    }
}
