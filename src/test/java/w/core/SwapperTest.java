package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.web.message.*;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;


/**
 * @author Frank
 * @date 2024/4/21 10:57
 */
class SwapperTest {

    Swapper swapper = Swapper.getInstance();;

    TestClass t = new TestClass();

    R r = new R();
    R2 r2 = new R2();

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
    public void watchTest() {
        WatchMessage watchMessage = new WatchMessage();
        watchMessage.setSignature("w.core.R#abstractParentMethod");
        Assertions.assertTrue(swapper.swap(watchMessage));
        WatchMessage watchMessage2 = new WatchMessage();
        watchMessage2.setSignature("w.core.R#interfaceMethod");
        Assertions.assertTrue(swapper.swap(watchMessage2));
        WatchMessage watchMessage3 = new WatchMessage();
        watchMessage3.setSignature("w.core.AbstractService#normalParentMethod");
        Assertions.assertTrue(swapper.swap(watchMessage3));
        WatchMessage watchMessage4 = new WatchMessage();
        watchMessage4.setSignature("w.core.MyInterface#interfaceDefaultMethod");
        Assertions.assertTrue(swapper.swap(watchMessage4));
        r.abstractParentMethod();
        r.interfaceMethod();
        r.normalParentMethod();
        r.interfaceDefaultMethod();
        r2.normalParentMethod();
        r2.interfaceDefaultMethod();
    }

    @Test
    public void outerWatchTest() {
        OuterWatchMessage message = new OuterWatchMessage();
        message.setSignature("w.core.TestClass#wrapperHello");
        message.setInnerSignature("*#hello");
        Assertions.assertTrue(swapper.swap(message));
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
        ChangeBodyMessage message = new ChangeBodyMessage();
        message.setClassName("w.core.TestClass");
        message.setMethod("wrapperHello");
        message.setParamTypes(Arrays.asList("java.lang.String"));
        message.setBody("{return java.util.UUID.randomUUID().toString();}");
        Assertions.assertTrue(swapper.swap(message));
        Assertions.assertTrue(t.wrapperHello("world").length() > 30);
    }

    @Test
    public void changeResultTest() {
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
        message.setBody("{w.Global.info(\"hello\");}");
        ExecBundle.changeBodyAndInvoke(0, message.getBody());
    }

    @Test
    public void replaceClassTest() throws Exception {
        ReplaceClassMessage message = new ReplaceClassMessage();
        message.setClassName("w.core.TestClass");
        message.setContent("yv66vgAAADQAJAoACQAYBwAZCgACABgIABoKAAIAGwoAAgAcCgAIAB0HAB4HAB8BAAY8aW5pdD4B" +
                "AAMoKVYBAARDb2RlAQAPTGluZU51bWJlclRhYmxlAQASTG9jYWxWYXJpYWJsZVRhYmxlAQAEdGhp" +
                "cwEAEkx3L2NvcmUvVGVzdENsYXNzOwEABWhlbGxvAQAmKExqYXZhL2xhbmcvU3RyaW5nOylMamF2" +
                "YS9sYW5nL1N0cmluZzsBAARuYW1lAQASTGphdmEvbGFuZy9TdHJpbmc7AQAMd3JhcHBlckhlbGxv" +
                "AQAKU291cmNlRmlsZQEADlRlc3RDbGFzcy5qYXZhDAAKAAsBABdqYXZhL2xhbmcvU3RyaW5nQnVp" +
                "bGRlcgEAA2hpIAwAIAAhDAAiACMMABEAEgEAEHcvY29yZS9UZXN0Q2xhc3MBABBqYXZhL2xhbmcv" +
                "T2JqZWN0AQAGYXBwZW5kAQAtKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL1N0cmluZ0J1" +
                "aWxkZXI7AQAIdG9TdHJpbmcBABQoKUxqYXZhL2xhbmcvU3RyaW5nOwAhAAgACQAAAAAAAwABAAoA" +
                "CwABAAwAAAAvAAEAAQAAAAUqtwABsQAAAAIADQAAAAYAAQAAAAcADgAAAAwAAQAAAAUADwAQAAAA" +
                "AQARABIAAQAMAAAASAACAAIAAAAUuwACWbcAAxIEtgAFK7YABbYABrAAAAACAA0AAAAGAAEAAAAK" +
                "AA4AAAAWAAIAAAAUAA8AEAAAAAAAFAATABQAAQABABUAEgABAAwAAAA6AAIAAgAAAAYqK7YAB7AA" +
                "AAACAA0AAAAGAAEAAAAOAA4AAAAWAAIAAAAGAA8AEAAAAAAABgATABQAAQABABYAAAACABc=");
        Assertions.assertTrue(swapper.swap(message));
        Assertions.assertEquals("hi frank", t.hello("frank"));
    }

    @Test
    public void traceRecursiveTest() {
        TraceMessage message = new TraceMessage();
        message.setSignature("w.core.R#recursive");
        message.setIgnoreZero(false);
        Assertions.assertTrue(swapper.swap(message));
        for (int i = 0; i < 10; i++) {
            r.recursive(3);
        }
    }
}