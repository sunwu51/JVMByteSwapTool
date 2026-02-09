package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.web.message.DecompileMessage;

import java.lang.instrument.Instrumentation;

/**
 * @author Frank
 * @date 2024/6/30 22:57
 */
public class DecompileTest {

    ChangeTarget target = new ChangeTarget();

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
    public void test() {
        new WatchTarget();
        DecompileMessage msg = new DecompileMessage();
        msg.setClassName("w.core.ChangeTarget");
        Assertions.assertTrue(swapper.swap(msg));
        Assertions.assertTrue(swapper.swap(msg));
    }
}
