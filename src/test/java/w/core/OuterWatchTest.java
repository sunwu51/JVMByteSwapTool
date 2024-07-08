package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.web.message.OuterWatchMessage;
import w.web.message.WatchMessage;

import java.lang.instrument.Instrumentation;

/**
 * @author Frank
 * @date 2024/6/24 23:06
 */
public class OuterWatchTest {

    WatchTarget target = new WatchTarget();

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
    }

    @Test
    public void test() {
        OuterWatchMessage msg = new OuterWatchMessage();
        msg.setSignature("w.core.WatchTarget#subMethodCall");
        msg.setInnerSignature("w.core.WatchTarget#getAge");
        Assertions.assertTrue(swapper.swap(msg));


        OuterWatchMessage msg2 = new OuterWatchMessage();
        msg2.setSignature("w.core.WatchTarget#subMethodCall");
        msg2.setInnerSignature("*#add");
        msg2.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg2));

        OuterWatchMessage msg3 = new OuterWatchMessage();
        msg3.setSignature("w.core.WatchTarget#subMethodCall");
        msg3.setInnerSignature("*#div");
        msg3.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg3));

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
        Assertions.assertTrue(swapper.swap(msg));
        target.doubleMethodWithParams(0.1);

    }

    @Test
    public void doubleReturnAndArrayParam2() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#doubleMethodWithParams");
        msg.setPrintFormat(2);
        Assertions.assertTrue(swapper.swap(msg));
        target.doubleMethodWithParams(0.1);
    }
    @Test
    public void toStringTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#toString");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(new WatchTarget().toString());
    }

    @Test
    public void runTest() {
        WatchMessage msg = new WatchMessage();
        msg.setSignature("w.core.WatchTarget#run");
        Assertions.assertTrue(swapper.swap(msg));
        target.run();
    }
}
