package w;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class GlobalJsonTest {
    @Test
    public void toJsonShouldWriteReferenceForCycle() {
        Node node = new Node("root");
        node.child = node;

        String json = Global.toJson(node);

        Assertions.assertTrue(json.contains("\"$ref\""));
    }

    @Test
    public void toJsonShouldReturnDiagnosticTextForTooDeepObjectGraph() {
        Node root = new Node("root");
        Node current = root;
        for (int i = 0; i < 3000; i++) {
            current.child = new Node("node-" + i);
            current = current.child;
        }

        String json = Global.toJson(root);

        Assertions.assertTrue(json.startsWith("toJson error: "));
        Assertions.assertNotEquals("toJson error", json);
    }

    @Test
    public void stashShouldStoreAndRemoveDiagnosticObjects() {
        Global.clearStash();

        Object value = new Object();
        Assertions.assertSame(value, Global.stash("value", value));
        Assertions.assertSame(value, Global.stash("value"));
        Assertions.assertSame(value, Global.unstash("value"));
        Assertions.assertNull(Global.stash("value"));
    }

    @Test
    public void ognlShouldUseFreshContextWithVariables() throws Exception {
        Map<String, Object> variables = new HashMap<>();
        variables.put("value", "from-variable");

        Assertions.assertEquals("from-variable", Global.ognl("#value", new Object(), variables));
        Assertions.assertNull(Global.ognl("#value", new Object()));
    }

    private static class Node {
        public String name;

        public Node child;

        Node(String name) {
            this.name = name;
        }
    }
}
