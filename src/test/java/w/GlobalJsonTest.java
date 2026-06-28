package w;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class GlobalJsonTest {
    @BeforeAll
    public static void setUp() {
        Global.instrumentation = ByteBuddyAgent.install();
    }

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
    public void toJsonWithDepthShouldSerializePrimitiveArrayArguments() {
        String json = Global.toJson(new Object[]{new double[]{0.25}}, 3);

        Assertions.assertFalse(json.startsWith("toJson error: "));
        Assertions.assertTrue(json.contains("0.25"));
    }

    @Test
    public void toJsonWithDepthOneShouldSerializeRootObjectFields() {
        Node root = new Node("root");
        root.child = new Node("child");

        String json = Global.toJson(root, 1);

        Assertions.assertFalse(json.startsWith("toJson error: "));
        Assertions.assertTrue(json.contains("\"name\":\"root\""));
    }

    @Test
    public void toJsonWithDepthShouldReplaceValuesBeyondMaxDepth() {
        Node root = new Node("root");
        root.child = new Node("child");
        root.child.child = new Node("grandchild");

        String json = Global.toJson(root, 1);

        Assertions.assertFalse(json.startsWith("toJson error: "));
        Assertions.assertTrue(json.contains("\"name\":\"root\""));
        Assertions.assertTrue(json.contains("\"<max depth reached>\""));
        Assertions.assertFalse(json.contains("grandchild"));
    }

    @Test
    public void toJsonWithDepthShouldKeepReferenceDetection() {
        Node node = new Node("root");
        node.child = node;

        String json = Global.toJson(node, 3);

        Assertions.assertFalse(json.startsWith("toJson error: "));
        Assertions.assertTrue(json.contains("\"$ref\""));
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

    @Test
    public void threadLocalsShouldDumpCurrentThreadValues() {
        ThreadLocal<String> local = new ThreadLocal<>();
        InheritableThreadLocal<String> inheritable = new InheritableThreadLocal<>();
        local.set("local-value");
        inheritable.set("inheritable-value");

        try {
            Map<String, Object> dump = Global.threadLocals();

            Assertions.assertEquals(Thread.currentThread().getName(), dump.get("thread"));
            Assertions.assertTrue(dumpContainsValue(dump, "threadLocal", "local-value"));
            Assertions.assertTrue(dumpContainsValue(dump, "inheritableThreadLocal", "inheritable-value"));
        } finally {
            local.remove();
            inheritable.remove();
        }
    }

    @Test
    public void threadLocalsShouldBeCallableFromOgnl() throws Exception {
        ThreadLocal<String> local = new ThreadLocal<>();
        local.set("ognl-local-value");

        try {
            Object dump = Global.ognl("@w.Global@threadLocals()", new Object());

            Assertions.assertTrue(dump instanceof Map);
            Assertions.assertTrue(dumpContainsValue((Map<String, Object>) dump, "threadLocal", "ognl-local-value"));
        } finally {
            local.remove();
        }
    }

    @Test
    public void threadLocalsShouldBeSafeToJson() {
        ThreadLocal<Object> local = new ThreadLocal<>();
        local.set(new Object() {
            @Override
            public String toString() {
                return "json-local-value";
            }
        });

        try {
            String json = Global.toJson(Global.threadLocals(), 3);

            Assertions.assertFalse(json.startsWith("toJson error: "));
            Assertions.assertTrue(json.contains("json-local-value"));
            Assertions.assertTrue(json.contains("\"valueClass\""));
            Assertions.assertFalse(json.contains("\"value\":"));
        } finally {
            local.remove();
        }
    }

    private static boolean dumpContainsValue(Map<String, Object> dump, String group, Object expectedValue) {
        Object entries = dump.get(group);
        if (!(entries instanceof Iterable)) {
            return false;
        }
        for (Object entry : (Iterable<?>) entries) {
            if (!(entry instanceof Map)) {
                continue;
            }
            if (expectedValue.equals(((Map<?, ?>) entry).get("valueString"))) {
                return true;
            }
        }
        return false;
    }

    private static class Node {
        public String name;

        public Node child;

        Node(String name) {
            this.name = name;
        }
    }
}
