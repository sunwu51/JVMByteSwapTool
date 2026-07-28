package w.core.debug;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import w.core.TestLoaders;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.UUID;

class DebugTransformerTest {
    @Test
    void injectsOnlyDiagnosticBridgeCallbacksAtSelectedLine() throws Exception {
        byte[] bytes = TestLoaders.classBytes("w/core/debug/DebugTarget.class");
        int line = firstLine(bytes, "calculate");
        DebugTransformer transformer = new DebugTransformer(
                UUID.randomUUID().toString(),
                DebugTarget.class.getName(),
                DebugTarget.class.getClassLoader(),
                "calculate", "(I)I", line);

        byte[] transformed = transformer.transform(bytes);
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        int hitCalls = 0;
        int enterCalls = 0;
        int exitCalls = 0;
        int arrayCreations = 0;
        for (MethodNode method : node.methods) {
            if (!"calculate".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("w/bridge/DiagnosticBridge".equals(call.owner)
                            && "debugHit".equals(call.name)) {
                        hitCalls++;
                        Assertions.assertEquals(
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;"
                                        + "[Ljava/lang/Object;[Ljava/lang/String;[Ljava/lang/String;"
                                        + "[Ljava/lang/Object;)V", call.desc);
                    } else if ("w/bridge/DiagnosticBridge".equals(call.owner)
                            && "debugEnter".equals(call.name)) {
                        enterCalls++;
                    } else if ("w/bridge/DiagnosticBridge".equals(call.owner)
                            && "debugExit".equals(call.name)) {
                        exitCalls++;
                    }
                }
                if (instruction instanceof TypeInsnNode
                        && instruction.getOpcode() == org.objectweb.asm.Opcodes.ANEWARRAY) {
                    arrayCreations++;
                }
            }
        }
        Assertions.assertEquals(1, hitCalls);
        Assertions.assertEquals(1, enterCalls);
        Assertions.assertEquals(2, exitCalls);
        Assertions.assertEquals(4, arrayCreations);

        Class<?> transformedType = new ByteArrayLoader().define(transformed);
        Method calculate = transformedType.getDeclaredMethod("calculate", int.class);
        calculate.setAccessible(true);
        Constructor<?> constructor = transformedType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Assertions.assertEquals(7, calculate.invoke(constructor.newInstance(), 3));
    }

    private int firstLine(byte[] bytes, String methodName) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!methodName.equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode) {
                    return ((LineNumberNode) instruction).line;
                }
            }
        }
        throw new AssertionError("line not found");
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(DebugTransformerTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
