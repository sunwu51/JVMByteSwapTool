package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.objectweb.asm.*;
import w.Global;
import w.core.asm.SbNode;
import w.core.asm.WAdviceAdapter;
import w.web.message.OuterWatchMessage;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;


/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class OuterWatchTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient OuterWatchMessage message;

    String method;

    String innerClassName;

    String innerMethod;

    int printFormat;


    public OuterWatchTransformer(OuterWatchMessage watchMessage) {
        this.message = watchMessage;
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.innerClassName = watchMessage.getInnerSignature().split("#")[0];
        this.innerMethod = watchMessage.getInnerSignature().split("#")[1];
        this.printFormat = watchMessage.getPrintFormat();
        this.traceId = watchMessage.getId();
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

        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method)) return mv;
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int line;
                    private int startTimeVarIndex;

                    private int paramsVarIndex;

                    private int returnValueVarIndex;

                    private int exceptionStringIndex;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        super.visitLineNumber(line, start);
                        this.line = line;
                    }

                    @Override
                    public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
                        boolean hit = (owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                                && name.equals(innerMethod);
                        if (hit) {
                            // long start = System.currentTimeMillis();
                            startTimeVarIndex = asmStoreStartTime(mv);
                            // String params = Arrays.toString(paramArray);
                            paramsVarIndex = asmSubCallStoreParamsString(mv, printFormat, descriptor);

                            mv.visitLdcInsn(traceId);
                            mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "fillCurThread", "(Ljava/lang/String;)V", false);


                            Label tryStart = new Label();
                            Label tryEnd = new Label();
                            Label catchStart = new Label();
                            mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable");
                            mv.visitLabel(tryStart);
                            // execute original method
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);

                            returnValueVarIndex = asmStoreRetString(mv, descriptor, printFormat);

                            // long duration = System.currentTimeMillis() - start;
                            int durationVarIndex = asmCalculateCost(mv, startTimeVarIndex);

                            // return value duplication
                            int returnValueVarIndex = asmStoreRetString(mv, descriptor, printFormat);
                            // new StringBuilder().append("line:" + line + ", request: ").append(params).append(", response: ").append(returnValue).append(", cost: ").append(duration).append("ms");
                            List<SbNode> list = new ArrayList<SbNode>();
                            list.add(new SbNode("line:" + line + ", req: "));
                            list.add(new SbNode(ALOAD, paramsVarIndex));
                            list.add(new SbNode(", response: "));
                            list.add(new SbNode(ALOAD, returnValueVarIndex));
                            list.add(new SbNode(", cost: "));
                            list.add(new SbNode(LLOAD, durationVarIndex));
                            list.add(new SbNode("ms"));
                            asmGenerateStringBuilder(mv, list);

                            /*---------------------counter: if reach the limitation will remove the transformer----------------*/
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "checkCountAndUnload", "(Ljava/lang/String;)V", false);

                            // info the string builder
                            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "info", "(Ljava/lang/Object;)V", false);


                            mv.visitLabel(tryEnd);
                            Label end = new Label();
                            mv.visitJumpInsn(Opcodes.GOTO, end);

                            mv.visitLabel(catchStart);
                            int exceptionIndex = newLocal(Type.getType(Throwable.class));
                            mv.visitVarInsn(Opcodes.ASTORE, exceptionIndex);
                            mv.visitVarInsn(Opcodes.ALOAD, exceptionIndex);
                            exceptionStringIndex = newLocal(Type.getType(String.class));
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                            mv.visitVarInsn(Opcodes.ASTORE, exceptionStringIndex);

                            postProcess(true);
                            mv.visitVarInsn(Opcodes.ALOAD, exceptionIndex);
                            mv.visitInsn(Opcodes.ATHROW);
                            Label catchEnd = new Label();
                            mv.visitLabel(catchEnd);
                            mv.visitLabel(end);

                            mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "clearRequestCtx", "()V", false);
                        } else {
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                        }
                    }

                    private void postProcess(boolean whenThrow) {
                        push(line);
                        loadLocal(startTimeVarIndex, Type.LONG_TYPE);
                        push(uuid.toString());
                        push(traceId);
                        push(innerClassName.substring(innerClassName.lastIndexOf('.') + 1) + "#" + innerMethod);
                        loadLocal(paramsVarIndex, Type.getType(String.class));

                        if (whenThrow) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            mv.visitVarInsn(ALOAD, exceptionStringIndex);
                        } else {
                            mv.visitVarInsn(ALOAD, returnValueVarIndex);
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }

                        mv.visitMethodInsn(INVOKESTATIC, "w/core/asm/Tool", "outerWatchPostProcess", "(IJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
                    }

                };
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] result = classWriter.toByteArray();
        status = 1;
        return result;
    }

    public boolean equals(Object other) {
        if (other instanceof OuterWatchTransformer) {
            return this.uuid.equals(((OuterWatchTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "OuterWatch_" + getClassName() + "#" + method;
    }
}
