package w.core.model;

import javassist.*;
import lombok.Data;
import org.objectweb.asm.*;
import w.Global;
import w.core.asm.SbNode;
import w.core.asm.WAdviceAdapter;
import w.web.message.WatchMessage;


import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;


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

    public WatchTransformer(WatchMessage watchMessage) {
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.message = watchMessage;
        this.traceId = watchMessage.getId();
        this.printFormat = watchMessage.getPrintFormat();
        this.minCost = watchMessage.getMinCost();
    }

    @Override
    public byte[] transform(Class<?> claz, byte[] origin) throws Exception {
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method)) return mv;
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int startTimeVarIndex;
                    private int paramsVarIndex;

                    @Override
                    protected void onMethodEnter() {
                        /*---------------------startTime:long start = System.currentTimeMillis();-----------------*/
                        startTimeVarIndex = asmStoreStartTime(mv);
                        /*---------------------param: String params = Arrays.toString(paramsArray);-----------------*/
                        paramsVarIndex = asmStoreParamsString(mv, printFormat);
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        /*---------------------cost:     long end = System.currentTimeMillis(); long duration = end - start;-----------------*/
                        int durationVarIndex = asmCalculateCost(mv, startTimeVarIndex);
                        Label elseLabel = new Label();
                        Label endLabel = new Label();
                        mv.visitVarInsn(Opcodes.LLOAD, durationVarIndex);
                        mv.visitLdcInsn((long) minCost);
                        mv.visitInsn(Opcodes.LCMP);
                        // if duration < minCost goto elselabel
                        mv.visitJumpInsn(Opcodes.IFLT, elseLabel);

                        /*---------------------returnValue: return object tostring, return the variable index------------*/
                        int returnValueVarIndex = asmStoreRetString(mv, descriptor, printFormat);

                        /*---------------------counter: if reach the limitation will remove the transformer----------------*/
                        mv.visitLdcInsn(uuid.toString());
                        mv.visitMethodInsn(INVOKESTATIC, "w/Global", "checkCountAndUnload", "(Ljava/lang/String;)V", false);

                        /*---------------------print: concat the final string, and w.Global.info-----------------*/
                        List<SbNode> list = new ArrayList<>();
                        list.add(new SbNode("request: "));
                        list.add(new SbNode(ALOAD, paramsVarIndex));
                        list.add(new SbNode(", response: "));
                        list.add(returnValueVarIndex < 0 ? new SbNode("null"): new SbNode(ALOAD, returnValueVarIndex));
                        list.add(new SbNode(", cost: "));
                        list.add(new SbNode(LLOAD, durationVarIndex));
                        list.add(new SbNode("ms"));
                        asmGenerateStringBuilder(mv, list);
                        mv.visitMethodInsn(INVOKESTATIC, "w/Global", "info", "(Ljava/lang/Object;)V", false);
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

                        /*-----------------------cost < minCost skip---------------------------------------------*/
                        mv.visitLabel(elseLabel);
                        mv.visitLabel(endLabel);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] result = classWriter.toByteArray();
        new FileOutputStream("T.class").write(result);
        status = 1;
        return result;
    }

    private void addWatchCodeToMethod(CtMethod ctMethod) throws CannotCompileException, NotFoundException {
        ctMethod.addLocalVariable("startTime", CtClass.longType);
        ctMethod.addLocalVariable("endTime", CtClass.longType);
        ctMethod.addLocalVariable("duration", CtClass.longType);
        ctMethod.addLocalVariable("req", Global.classPool.get("java.lang.String"));
        ctMethod.addLocalVariable("res", Global.classPool.get("java.lang.String"));
        ctMethod.insertBefore("startTime = System.currentTimeMillis();");
        ctMethod.insertBefore("w.Global.checkCountAndUnload(\"" + uuid + "\");\n");

        StringBuilder afterCode = new StringBuilder("{\n")
                .append("endTime = System.currentTimeMillis();\n")
                .append("duration = endTime - startTime;\n");

        afterCode.append("if (duration>=").append(minCost).append(") {\n");
        StringBuilder catchCode = new StringBuilder("{\n");
        if (printFormat == 1) {
            afterCode.append("req = Arrays.toString($args);");
            afterCode.append("res = \"\" + $_;");
            catchCode.append("String req = Arrays.toString($args);");
        } else if (printFormat == 2) {
            afterCode.append("req = w.Global.toJson($args);");
            afterCode.append("res = w.Global.toJson(($w)$_);");
            catchCode.append("String req = w.Global.toJson($args);");
        } else {
            afterCode.append("req = w.Global.toString($args);");
            afterCode.append("res = w.Global.toString(($w)$_);");
            catchCode.append("String req = w.Global.toString($args);");
        }
        afterCode.append("w.util.RequestUtils.fillCurThread(\"").append(message.getId()).append("\");")
                .append("w.Global.info(\"cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);")
                .append("w.util.RequestUtils.clearRequestCtx();")
                .append("}");
        afterCode.append("}");
        catchCode.append("w.util.RequestUtils.fillCurThread(\"").append(message.getId()).append("\");")
                .append("w.Global.info(\"req:\"+req+\",exception:\"+$e); throw $e;")
                .append("w.util.RequestUtils.clearRequestCtx();")
                .append("}");
        ctMethod.insertAfter(afterCode.toString());
        if (Global.nonVerifying) {
            ctMethod.addCatch(catchCode.toString(), Global.classPool.get("java.lang.Throwable"));
        }
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
//
//    public static class TimingMethodAdapter extends LocalVariablesSorter {
//        private int startTimeIndex;
//
//        private int paramsIndex;
//
//        private String methodDescriptor;
//
//        protected TimingMethodAdapter(int api, int access, String name, String descriptor, MethodVisitor mv) {
//            super(api, access, descriptor, mv);
//            this.methodDescriptor = descriptor;
//
//
//        }
//
//        @Override
//        public void visitCode() {
//            super.visitCode();
//            startTimeIndex = newLocal(Type.LONG_TYPE);
//            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
//            mv.visitVarInsn(Opcodes.LSTORE, startTimeIndex); //LSTORE: save a long value to the index
//            mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
//            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
//            mv.visitVarInsn(Opcodes.ASTORE, 7);
//
//
//
////            // 准备拼接入参信息
//
////            mv.visitVarInsn(Opcodes.ASTORE, 8);
////            mv.visitLdcInsn("Method: " + methodDescriptor + ", Params: ");
////            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
//////
//
//        }
//
//        @Override
//        public void visitInsn(int opcode) {
//            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
//                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
//                mv.visitVarInsn(Opcodes.LLOAD, startTimeIndex); // LLOAD: load a long value from the index, this is just the startTime
//                mv.visitInsn(Opcodes.LSUB);
//                // end - start
//                int durationIndex = newLocal(Type.LONG_TYPE);
//                mv.visitVarInsn(Opcodes.LSTORE, durationIndex); // save the duration
//
////                if (opcode != Opcodes.RETURN) {
////                    mv.visitInsn(Opcodes.DUP);
////                    Type returnType = Type.getReturnType(methodDescriptor);
////                    box(returnType);
////                    mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
////                }
//
//
//                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
//                mv.visitInsn(Opcodes.DUP);
//                mv.visitLdcInsn("Method executed in ");
//                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
//                mv.visitVarInsn(Opcodes.LLOAD, durationIndex);
//                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", false);
//                mv.visitLdcInsn(" ns");
//                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
//                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
//
//                // 调用 w.Global.info 方法，将 String 作为 Object 传递
//                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "w/Global", "info", "(Ljava/lang/Object;)V", false);
//            }
//            super.visitInsn(opcode);
//        }
//    }


}
