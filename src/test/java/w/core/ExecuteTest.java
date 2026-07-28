package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.web.message.ExecMessage;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;

/**
 * @author Frank
 * @date 2024/6/30 20:43
 */
public class ExecuteTest {


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
    public void test() throws Exception {
        ExecMessage message = new ExecMessage();
        message.setBody("package w; public class Exec{ public void exec() { w.Global.info(\"hello\");}  }");
        ExecBundle.changeBodyAndInvoke(message.getBody());
    }

    @Test
    public void shouldCompileAndCacheExecPerTargetClassLoader() throws Exception {
        byte[] bytes = TestLoaders.classBytes("isolated/ExecTarget.class");
        ClassLoader first = new TestLoaders.NullParentLoader("isolated.ExecTarget", bytes);
        ClassLoader second = new TestLoaders.NullParentLoader("isolated.ExecTarget", bytes);
        first.loadClass("isolated.ExecTarget");
        second.loadClass("isolated.ExecTarget");

        String body = "package w; public class Exec { public void exec() { "
                + "w.Global.stash(\"exec-loader\", new isolated.ExecTarget().loaderIdentity()); } }";
        ExecBundle.changeBodyAndInvoke(body, first);
        Assertions.assertEquals(ClassTargetResolver.identityHash(first), Global.stash("exec-loader"));

        ExecBundle.changeBodyAndInvoke(body, second);
        Assertions.assertEquals(ClassTargetResolver.identityHash(second), Global.stash("exec-loader"));
        Assertions.assertEquals(2, ExecBundle.contextCount());

        ExecBundle.changeBodyAndInvoke(body, first);
        Assertions.assertEquals(ClassTargetResolver.identityHash(first), Global.stash("exec-loader"));
        Assertions.assertEquals(2, ExecBundle.contextCount());
    }
}
