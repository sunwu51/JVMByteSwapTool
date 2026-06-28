package w.core.model;

import lombok.Data;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import w.Global;
import w.core.asm.Tool;
import w.core.asm.WAdviceAdapter;
import w.web.message.WatchMessage;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;


/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class WatchTransformer extends BaseClassTransformer {

    transient WatchMessage message;

    String method;

    int printFormat;

    int minCost;

    String ognl;

    int depthForJson;

    Map<String, String> variables;

    public WatchTransformer(WatchMessage watchMessage) {
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.message = watchMessage;
        this.traceId = watchMessage.getId();
        this.printFormat = watchMessage.getPrintFormat();
        this.minCost = watchMessage.getMinCost();
        this.ognl = watchMessage.getOgnl();
        this.depthForJson = watchMessage.getDepthForJson() <= 0 ? 3 : watchMessage.getDepthForJson();
        this.variables = watchMessage.getVariables();
        Tool.registerOgnlVariables(uuid.toString(), variables);
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return Global.getClassLoader();
            }
        };

        AtomicBoolean effect = new AtomicBoolean();
        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method)) {
                    return mv;
                }
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int startTimeVarIndex;
                    private int paramsVarIndex;

                    private int paramsArrayVarIndex;

                    private int returnValueVarIndex = -1;

                    private int returnObjectVarIndex = -1;

                    private int exceptionStringIndex = -1;

                    private int exceptionObjectIndex = -1;

                    private int methodSignatureVarIndex;
                    private final Label startTry = new Label();
                    private final Label endTry = new Label();
                    private final Label startCatch = new Label();
                    private final Label endCatch = new Label();

                    @Override
                    protected void onMethodEnter() {
                        /*---------------------startTime:long start = System.currentTimeMillis();-----------------*/
                        startTimeVarIndex = asmStoreStartTime(mv);
                        /*---------------------param: String params = Arrays.toString(paramsArray);-----------------*/
                        paramsArrayVarIndex = asmStoreArgArray();
                        paramsVarIndex = asmStoreObjectString(paramsArrayVarIndex, printFormat, depthForJson, true);
                        methodSignatureVarIndex = newLocal(Type.getType(String.class));
                        String methodSignature = className.substring(1 + className.lastIndexOf('.')) +"#" + method;
                        mv.visitLdcInsn(methodSignature);
                        mv.visitVarInsn(ASTORE, methodSignatureVarIndex);
                        mv.visitLdcInsn(traceId);
                        mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "fillCurThread", "(Ljava/lang/String;)V", false);
                        // try { original }
                        mv.visitLabel(startTry);
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        /*---------------------returnValue: return object tostring, return the variable index------------*/
                        if (opcode != ATHROW) {
                            returnObjectVarIndex = asmStoreRetObject(mv, descriptor);
                            returnValueVarIndex = asmStoreObjectString(returnObjectVarIndex, printFormat, depthForJson);
                            postProcess(false);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        mv.visitLabel(endTry);
                        mv.visitLabel(startCatch);
                        exceptionObjectIndex = newLocal(Type.getType(Throwable.class));
                        mv.visitVarInsn(Opcodes.ASTORE, exceptionObjectIndex);
                        mv.visitVarInsn(Opcodes.ALOAD, exceptionObjectIndex);
                        exceptionStringIndex = newLocal(Type.getType(String.class));
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                        mv.visitVarInsn(Opcodes.ASTORE, exceptionStringIndex);

                        postProcess(true);
                        mv.visitVarInsn(Opcodes.ALOAD, exceptionObjectIndex);
                        mv.visitInsn(Opcodes.ATHROW);
                        mv.visitLabel(endCatch);
                        mv.visitTryCatchBlock(startTry, endTry, startCatch, "java/lang/Throwable");
                        effect.set(true);
                        super.visitMaxs(maxStack, maxLocals);
                    }

                    private void postProcess(boolean whenThrow) {
                        loadLocal(startTimeVarIndex, Type.LONG_TYPE);
                        push(minCost);
                        push(uuid.toString());
                        push(traceId);
                        loadLocal(methodSignatureVarIndex, Type.getType(String.class));
                        loadLocal(paramsVarIndex, Type.getType(String.class));

                        if (whenThrow) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            loadLocal(exceptionStringIndex, Type.getType(String.class));
                        } else {
                            loadLocal(returnValueVarIndex, Type.getType(String.class));
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                        loadThisOrNull();
                        push(ognl == null ? "" : ognl);
                        push(printFormat);
                        loadLocal(paramsArrayVarIndex, Type.getType(Object[].class));
                        if (whenThrow) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            loadLocal(exceptionObjectIndex, Type.getType(Throwable.class));
                        } else {
                            loadLocal(returnObjectVarIndex, Type.getType(Object.class));
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                        push(depthForJson);
                        mv.visitMethodInsn(INVOKESTATIC, "w/core/asm/Tool", "watchPostProcess", "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;I[Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Throwable;I)V", false);
                    }

                    private void loadThisOrNull() {
                        if ((access & Opcodes.ACC_STATIC) == 0) {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                        } else {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                    }

                };
            }
        }, ClassReader.EXPAND_FRAMES );
        byte[] result = classWriter.toByteArray();
        if (!effect.get()) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        status = 1;
        return result;
    }

    public boolean equals(Object other) {
        if (other instanceof WatchTransformer) {
            return this.uuid.equals(((WatchTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "Watch_" + getClassName() + "#" + method;
    }

    @Override
    public void clear() {
        Tool.unregisterOgnlVariables(uuid.toString());
    }

}
