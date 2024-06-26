package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.constant.Codes;
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;

public class ChangeResultTest {


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
    public void javassistTest() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("add");
        msg.setInnerClassName("*");
        msg.setBody("{$_=10086;}");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(target.addWrapper(1,1));
    }

    @Test
    public void asmTest() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setMode(Codes.changeResultModeUseASM);
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("add");
        msg.setInnerClassName("*");
        msg.setBody("{return 10086;}");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(target.addWrapper(1,1));
    }
}
