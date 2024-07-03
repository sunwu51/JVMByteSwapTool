package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import org.objectweb.asm.*;
import w.Global;
import w.core.asm.SbNode;
import w.core.asm.WAdviceAdapter;
import w.web.message.OuterWatchMessage;

import java.io.FileOutputStream;
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
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

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

//                            postProcess(false);


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
//                            int durationVarIndexInCatch = asmCalculateCost(mv, startTimeVarIndex);
//                            List<SbNode> listInCatch = new ArrayList<SbNode>();
//                            listInCatch.add(new SbNode("line:" + line + ", req: "));
//                            listInCatch.add(new SbNode(ALOAD, paramsVarIndex));
//                            listInCatch.add(new SbNode(", throw: "));
//                            listInCatch.add(new SbNode(ALOAD, exceptionStringIndex));
//                            listInCatch.add(new SbNode(", cost: "));
//                            listInCatch.add(new SbNode(LLOAD, durationVarIndexInCatch));
//                            listInCatch.add(new SbNode("ms"));
//                            asmGenerateStringBuilder(mv, listInCatch);
//                            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "info", "(Ljava/lang/Object;)V", false);
//                            /*---------------------counter: if reach the limitation will remove the transformer----------------*/
//                            mv.visitLdcInsn(uuid.toString());
//                            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "checkCountAndUnload", "(Ljava/lang/String;)V", false);
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
//        new FileOutputStream("T.class").write(result);
        status = 1;
        return result;
    }

    private void addOuterWatchCodeToMethod(CtMethod ctMethod) throws CannotCompileException, NotFoundException {
        ctMethod.instrument(new ExprEditor() {
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getMethodName().equals(innerMethod)) {
                    if (innerClassName.equals("*") || m.getClassName().equals(innerClassName)) {
                        String code = "{" +
                                "w.Global.checkCountAndUnload(\"" + uuid + "\");\n" +
                                "long start = System.currentTimeMillis();" +
                                "String req = null;" +
                                "String res = null;" +
                                "Throwable t = null;" +
                                "try {" +
                                "    $_ = $proceed($$);" +
                                "} catch (Throwable e) {" +
                                "   t = e;" +
                                "   throw e;" +
                                "} finally {" +
                                "long duration = System.currentTimeMillis() - start;" +
                                "int printFormat = " + printFormat +";" +
                                "if (printFormat == 1) {" +
                                "   req = Arrays.toString($args);" +
                                "   res = \"\" + $_;" +
                                "} else if (printFormat == 2) {" +
                                "req = w.Global.toJson($args);" +
                                "res = w.Global.toJson(($w)$_);" +
                                "} else {" +
                                "   req = w.Global.toString($args);" +
                                "   res = w.Global.toString(($w)$_);" +
                                "}"  +
                                "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");" +
                                "w.Global.info(\"line" + m.getLineNumber() + ",cost:\"+duration+\"ms,req:\"+req+\",res:\"+res+\",exception:\"+t);" +
                                "w.util.RequestUtils.clearRequestCtx();" +
                                "}}";
                     if (!Global.nonVerifying) {
                         code = "{" +
                                 "long start = System.currentTimeMillis();" +
                                 "$_ = $proceed($$);" +
                                 "long duration = System.currentTimeMillis() - start;" +
                                 "String req = null;" +
                                 "String res = null;" +
                                 "int printFormat = " + printFormat +";" +
                                 "if (printFormat == 1) {" +
                                 "   req = Arrays.toString($args);" +
                                 "   res = \"\" + $_;" +
                                 "} else if (printFormat == 2) {" +
                                 "req = w.Global.toJson($args);" +
                                 "res = w.Global.toJson(($w)$_);" +
                                 "} else {" +
                                 "   req = w.Global.toString($args);" +
                                 "   res = w.Global.toString(($w)$_);" +
                                 "}"  +
                                 "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");" +
                                 "w.Global.info(\"line" + m.getLineNumber() + ",cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);" +
                                 "w.util.RequestUtils.clearRequestCtx();" +
                                 "}";
                     }
                     m.replace(code);
                    }
                }
            }
        });
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
