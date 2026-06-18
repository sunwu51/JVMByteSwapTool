package w.core.asm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import w.core.constant.Codes;

public class ToolTest {
    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    @Test
    public void shouldReadMdcContextMapByReflection() {
        MDC.put("requestId", "r1");

        Assertions.assertTrue(Tool.getMdcContextMapString().contains("requestId=r1"));
    }

    @Test
    public void shouldRenderMdcContextMapAsJsonWhenPrintFormatIsJson() {
        MDC.put("requestId", "r1");

        String mdc = Tool.getMdcContextMapString(Codes.PRINT_FORMAT_FOR_TO_JSON);

        Assertions.assertTrue(mdc.contains("\"requestId\":\"r1\""));
        Assertions.assertFalse(mdc.contains("requestId=r1"));
    }

    @Test
    public void shouldReturnNullWhenMdcContextIsEmpty() {
        Assertions.assertEquals("null", Tool.getMdcContextMapString());
    }

    @Test
    public void shouldEvaluateOgnlWithRootObject() {
        Root root = new Root();
        root.name = "root-name";

        Assertions.assertEquals("root-name", Tool.getOgnlString(root, "name", 1));
    }

    public static class Root {
        public String name;
    }
}
