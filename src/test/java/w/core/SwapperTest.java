package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.constant.Codes;
import w.web.message.*;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;


/**
 * @author Frank
 * @date 2024/4/21 10:57
 */
class SwapperTest {

    Swapper swapper = Swapper.getInstance();

    TestClass t = new TestClass();

    R r = new R();
    R2 r2 = new R2();

    WatchTarget target = new WatchTarget();

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
    public void watchTest() {
        WatchMessage watchMessage = new WatchMessage();

        watchMessage.setSignature("w.core.WatchTarget#voidMethodWithNoParams");
        Assertions.assertTrue(swapper.swap(watchMessage));
        target.voidMethodWithNoParams();

//
//        watchMessage.setSignature("w.core.MyInterface#interfaceMethod");
//        Assertions.assertTrue(swapper.swap(watchMessage));
//
//        watchMessage = new WatchMessage();
//        watchMessage.setSignature("w.core.R#interfaceMethod");
//        Assertions.assertTrue(swapper.swap(watchMessage));
//
//
////        watchMessage = new WatchMessage();
////        watchMessage.setSignature("w.core.AbstractService#normalParentMethod");
////        Assertions.assertTrue(swapper.swap(watchMessage));
//
////        watchMessage = new WatchMessage();
////        watchMessage.setSignature("w.core.MyInterface#interfaceDefaultMethod");
////        Assertions.assertTrue(swapper.swap(watchMessage));
//
//        r.abstractParentMethod();
//        r.interfaceMethod();
//        r.normalParentMethod();
//        r.interfaceDefaultMethod();
//        r2.normalParentMethod();
//        r2.interfaceDefaultMethod();
//
////        WatchMessage watchMessage = new WatchMessage();
////        watchMessage.setSignature("w.core.TestClass#hello");
////        Assertions.assertTrue(swapper.swap(watchMessage));
////        new TestClass().hello("frank", "david", "Smith");
////        new TestClass().hello("frank", "david", "Smith");
////        new TestClass().hello("frank", "david", "Smith");
////        new TestClass().hello("frank", "david", "Smith");
////        new TestClass().hello("frank", "david", "Smith");
//
//        watchMessage = new WatchMessage();
//        watchMessage.setSignature("w.core.TestClass#generateRandom");
//        Assertions.assertTrue(swapper.swap(watchMessage));
//        new TestClass().generateRandom();
    }

    @Test
    public void outerWatchTest() {
        OuterWatchMessage message = new OuterWatchMessage();
        message.setSignature("w.core.TestClass#wrapperHello");
        message.setInnerSignature("*#hello");
        Assertions.assertTrue(swapper.swap(message));
        t.wrapperHello("world");
        t.wrapperHello("world");
        t.wrapperHello("world");
        t.wrapperHello("world");
    }

    @Test
    public void traceTest() {
        TraceMessage message = new TraceMessage();
        message.setSignature("w.core.TestClass#wrapperHello");
        message.setIgnoreZero(false);
        Assertions.assertTrue(swapper.swap(message));
        t.wrapperHello("world");
    }

    @Test
    public void changeBodyTest() {
        // javassist test
        ChangeBodyMessage message = new ChangeBodyMessage();
        message.setClassName("w.core.TestClass");
        message.setMethod("wrapperHello");
        message.setMode(Codes.changeBodyModeUseJavassist);
        message.setParamTypes(Arrays.asList("java.lang.String"));
        message.setBody("{return java.util.UUID.randomUUID().toString();}");
        Assertions.assertTrue(swapper.swap(message));
        Assertions.assertTrue(t.wrapperHello("world").length() > 30);
        System.out.println(t.wrapperHello("world"));

    }

    @Test
    public void changeBodyAsmTest() {
        // asm test
        ChangeBodyMessage message = new ChangeBodyMessage();
        message.setClassName("w.core.TestClass");
        message.setMethod("wrapperHello");
        message.setMode(Codes.changeBodyModeUseASM);
        message.setParamTypes(Arrays.asList("java.lang.String"));
        message.setBody("{return \"arg=\" + $1 + \", uuid=\" + java.util.UUID.randomUUID().toString();}");
        Assertions.assertTrue(swapper.swap(message));
        Assertions.assertTrue(t.wrapperHello("world").length() > 30);
        System.out.println(t.wrapperHello("world"));
    }

    @Test
    public void changeResultJavassistTest() {
        ChangeResultMessage message = new ChangeResultMessage();
        message.setClassName("w.core.TestClass");
        message.setMethod("wrapperHello");
        message.setParamTypes(Arrays.asList("java.lang.String"));
        message.setInnerClassName("*");
        message.setInnerMethod("hello");
        message.setBody("{$_ = java.util.UUID.randomUUID().toString();}");
        Assertions.assertTrue(swapper.swap(message));
        Assertions.assertTrue(t.wrapperHello("world").length() > 30);
    }

    @Test
    public void execTest() throws Exception {
        ExecMessage message = new ExecMessage();
        message.setBody("package w; public class Exec{ public void exec() { w.Global.info(\"hello\");}  }");
        ExecBundle.changeBodyAndInvoke(message.getBody());
    }

    @Test
    public void traceRecursiveTest() {
        System.setProperty("maxHit", "322");

        TraceMessage message = new TraceMessage();
        message.setSignature("w.core.R#recursive");
        message.setIgnoreZero(false);
        Assertions.assertTrue(swapper.swap(message));
        for (int i = 0; i < 10; i++) {
            r.recursive(3);
        }
    }


    public String asmTest() {
        if (1 >= 0) {
            Global.info(1);
        }
        return "1";
    }
}