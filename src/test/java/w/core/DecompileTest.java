package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.model.DecompileResult;
import w.core.model.SwapResult;
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
        SwapResult result = swapper.swap(msg);
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertNotNull(result.getData());
        Assertions.assertFalse(String.valueOf(result.getData()).contains("try again"));
        DecompileResult decompileResult = (DecompileResult) result.getData();
        Assertions.assertTrue(decompileResult.getInvocations().stream()
                .anyMatch(site -> site.getDeclaringClass().equals("w.core.ChangeTarget$1")));
    }
}
