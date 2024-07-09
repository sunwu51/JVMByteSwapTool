package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.web.message.ChangeBodyMessage;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;

public class ChangeBodyTest {


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
    public void javassistTest() {

        ChangeBodyMessage msg = new ChangeBodyMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("getName");
        msg.setMode(0);
        msg.setParamTypes(Arrays.asList());
        msg.setBody("{ return \"newName\";}");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(target.getName());
    }

    @Test
    public void asmTest() {
        ChangeBodyMessage msg = new ChangeBodyMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("getName");
        msg.setMode(1);
        msg.setParamTypes(Arrays.asList());
        msg.setBody("{ try {w.Global.readFile(\"3.xml\");} catch(Exception e) {} return \"123\";}");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(target.getName());
    }

    @Test
    public void asmTest2() throws IOException, InterruptedException {
        ChangeBodyMessage msg = new ChangeBodyMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("add");
        msg.setMode(1);
        msg.setParamTypes(Arrays.asList("int", "double"));
        msg.setBody("{return $1 + $2 + 100; }");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(target.add(1,1));
        Assertions.assertEquals(102.0, target.add(1, 1));
    }

    @Test
    public void asmTest4() throws IOException, InterruptedException {
        ChangeBodyMessage msg = new ChangeBodyMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("hashCode");
        msg.setMode(1);
        msg.setParamTypes(Arrays.asList());
        msg.setBody("{return 1; }");
        Assertions.assertTrue(swapper.swap(msg));
        System.out.println(target.hashCode());
        Assertions.assertEquals(1, target.hashCode());
    }
}
