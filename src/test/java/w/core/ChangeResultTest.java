package w.core;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import w.Global;
import w.core.constant.Codes;
import w.core.model.ChangeResultResult;
import w.core.model.SwapResult;
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
        msg.setBody("{try { $_ = 0;} catch (Exception e) {$_ = 1;}}");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        System.out.println(target.addWrapper(1,1));
    }


    @Test
    public void asmTest() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("add");
        msg.setInnerClassName("*");
        msg.setBody("try { $_ = 100 /(int)$_; } catch (Exception e) {w.Global.error(\"error\", e);$_ = 33;}");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        System.out.println(target.addWrapper(1,1));

    }

    @Test
    public void asmShouldSupportJavassistProceedAllArgsSyntax() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("add");
        msg.setInnerClassName("*");
        msg.setBody("$_ = $proceed($$) + 1;");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertEquals(10003.0, target.addWrapper(1, 1));
    }

    @Test
    public void asmTest2() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("hello");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList());
        msg.setInnerMethod("getName");
        msg.setInnerClassName("*");
        msg.setBody("{$_= \"10086\";}");
        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        System.out.println(target.hello());

    }

    @Test
    public void asmShouldChangeResultInsideNestedLambda() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("nestedLambdaResult");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList());
        msg.setInnerMethod("hello");
        msg.setInnerClassName("w.core.ChangeTarget");
        msg.setBody("$_ = \"changed-by-nested-lambda\";");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertEquals("changed-by-nested-lambda", target.nestedLambdaResult());
    }

    @Test
    public void javassistShouldChangeResultInsideNestedLambda() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("nestedLambdaResult");
        msg.setParamTypes(Arrays.asList());
        msg.setInnerMethod("hello");
        msg.setInnerClassName("w.core.ChangeTarget");
        msg.setBody("{$_= \"changed-by-javassist\";}");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertEquals("changed-by-javassist", target.nestedLambdaResult());
    }

    @Test
    public void asmShouldChangeResultInsideLambdaInAnonymousClass() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("lambdaInAnonymousResult");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList());
        msg.setInnerMethod("hello");
        msg.setInnerClassName("w.core.ChangeTarget");
        msg.setBody("$_ = \"changed-in-anonymous\";");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertEquals("changed-in-anonymous", target.lambdaInAnonymousResult());
    }

    @Test
    public void asmResultShouldReportEnhancedCallSites() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("add");
        msg.setInnerClassName("*");
        msg.setBody("$_ = 1;");

        SwapResult result = swapper.swap(msg);
        Assertions.assertTrue(result.isSuccess());
        ChangeResultResult data = (ChangeResultResult) result.getData();
        Assertions.assertNotNull(data);
        Assertions.assertFalse(data.getEnhancedCalls().isEmpty());
        ChangeResultResult.EnhancedCall call = data.getEnhancedCalls().get(0);
        Assertions.assertTrue(call.getLine() > 0);
        Assertions.assertTrue(call.getOuterSignature().contains("#addWrapper"));
        Assertions.assertTrue(call.getInnerSignature().contains("#add"));
    }

    @Test
    public void asmShouldFailWhenNoMatchingInnerCall() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("noSuchMethod");
        msg.setInnerClassName("*");
        msg.setBody("$_ = 1;");

        SwapResult result = swapper.swap(msg);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(String.valueOf(result.getMessage()).contains("noSuchMethod"));
    }

    @Test
    public void includeNestedFalseShouldOnlyChangeDirectOuterCall() throws IOException, InterruptedException {
        ChangeResultMessage msg = new ChangeResultMessage();
        msg.setClassName("w.core.ChangeTarget");
        msg.setMethod("addWrapper");
        msg.setMode(Codes.CHANGE_RESULT_MODE_USE_ASM);
        msg.setParamTypes(Arrays.asList("int", "int"));
        msg.setInnerMethod("add");
        msg.setInnerClassName("*");
        msg.setIncludeNested(false);
        msg.setBody("$_ = $proceed() + 1;");

        Assertions.assertTrue(swapper.swap(msg).isSuccess());
        Assertions.assertEquals(10003.0, target.addWrapper(1, 1));
    }
}
