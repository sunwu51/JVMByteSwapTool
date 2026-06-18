package w;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

        Assertions.assertTrue(json.startsWith("toJson error: JSONException: level too large"));
    }

    private static class Node {
        public String name;

        public Node child;

        Node(String name) {
            this.name = name;
        }
    }
}
