package w.core.model;

import lombok.Data;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import w.core.asm.WAdviceAdapter;
import w.core.watch.WatchConfig;
import w.core.watch.WatchRuntime;
import w.web.message.WatchMessage;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.objectweb.asm.Opcodes.ASM9;

@Data
public class WatchTransformer extends BaseClassTransformer {
    private static final String BRIDGE = "w/bridge/WatchBridge";
    private static final String EXIT_DESCRIPTOR =
            "(Ljava/lang/Object;JLjava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Throwable;)V";

    transient WatchMessage message;
    String method;
    int printFormat;
    int minCost;
    String ognl;
    int depthForJson;
    Map<String, String> variables;

    public WatchTransformer(WatchMessage watchMessage) {
        String signature = watchMessage.getSignature();
        int separator = signature == null ? -1 : signature.lastIndexOf('#');
        if (separator <= 0 || separator == signature.length() - 1) {
            throw new IllegalArgumentException("signature must be fully.qualified.ClassName#methodName");
        }
        this.className = signature.substring(0, separator);
        this.method = signature.substring(separator + 1);
        this.message = watchMessage;
        this.traceId = watchMessage.getId();
        this.printFormat = watchMessage.getPrintFormat();
        this.minCost = watchMessage.getMinCost();
        this.ognl = watchMessage.getOgnl();
        this.depthForJson = watchMessage.getDepthForJson() <= 0 ? 3 : watchMessage.getDepthForJson();
        this.variables = watchMessage.getVariables();
    }

    public WatchConfig config() {
        String shortSignature = className.substring(className.lastIndexOf('.') + 1) + "#" + method;
        return new WatchConfig(uuid.toString(), traceId, shortSignature, printFormat,
                minCost, ognl, depthForJson, variables);
    }

    @Override
    public byte[] transform(byte[] origin) {
        ClassReader reader = new ClassReader(origin);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return getTargetClassLoader();
            }
        };
        AtomicBoolean effect = new AtomicBoolean();
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method)) {
                    return delegate;
                }
                effect.set(true);
                return new BridgeAdvice(delegate, access, name, descriptor);
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!effect.get()) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        status = 1;
        return writer.toByteArray();
    }

    private final class BridgeAdvice extends WAdviceAdapter {
        private final int methodAccess;
        private final String descriptor;
        private final Label startTry = new Label();
        private final Label endTry = new Label();
        private final Label catchHandler = new Label();
        private int startIndex;
        private int tokenIndex;
        private int argsIndex;

        private BridgeAdvice(MethodVisitor visitor, int access, String name, String descriptor) {
            super(ASM9, visitor, access, name, descriptor);
            this.methodAccess = access;
            this.descriptor = descriptor;
        }

        @Override
        protected void onMethodEnter() {
            startIndex = asmStoreStartTime(mv);
            tokenIndex = newLocal(Type.getType(Object.class));
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, tokenIndex);
            argsIndex = newLocal(Type.getType(Object[].class));
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, argsIndex);
            mv.visitLabel(startTry);
            mv.visitLdcInsn(uuid.toString());
            mv.visitMethodInsn(INVOKESTATIC, BRIDGE, "enter",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, tokenIndex);
            loadArgArray();
            mv.visitVarInsn(ASTORE, argsIndex);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                return;
            }
            int resultIndex = asmStoreRetObject(mv, descriptor);
            emitExit(resultIndex, -1);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(endTry);
            mv.visitLabel(catchHandler);
            int throwableIndex = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, throwableIndex);
            emitExit(-1, throwableIndex);
            mv.visitVarInsn(ALOAD, throwableIndex);
            mv.visitInsn(ATHROW);
            mv.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");
            super.visitMaxs(maxStack, maxLocals);
        }

        private void emitExit(int resultIndex, int throwableIndex) {
            mv.visitVarInsn(ALOAD, tokenIndex);
            mv.visitVarInsn(LLOAD, startIndex);
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitVarInsn(ALOAD, argsIndex);
            if (resultIndex < 0) {
                mv.visitInsn(ACONST_NULL);
            } else {
                mv.visitVarInsn(ALOAD, resultIndex);
            }
            if (throwableIndex < 0) {
                mv.visitInsn(ACONST_NULL);
            } else {
                mv.visitVarInsn(ALOAD, throwableIndex);
            }
            mv.visitMethodInsn(INVOKESTATIC, BRIDGE, "exit", EXIT_DESCRIPTOR, false);
        }
    }

    @Override
    public String desc() {
        return "Watch_" + getClassName() + "#" + method;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WatchTransformer
                && uuid.equals(((WatchTransformer) other).getUuid());
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public void clear() {
        WatchRuntime.unregister(uuid.toString());
    }
}
