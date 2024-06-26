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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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

        AtomicBoolean effect = new AtomicBoolean();
        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method)) return mv;
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int startTimeVarIndex;
                    private int paramsVarIndex;
                    private int returnValueVarIndex;
                    Label startTry = new Label();
                    Label endTry = new Label();
                    Label startCatch = new Label();
                    Label endCatch = new Label();

                    @Override
                    protected void onMethodEnter() {
                        /*---------------------startTime:long start = System.currentTimeMillis();-----------------*/
                        startTimeVarIndex = asmStoreStartTime(mv);
                        /*---------------------param: String params = Arrays.toString(paramsArray);-----------------*/
                        paramsVarIndex = asmStoreParamsString(mv, printFormat);
                        returnValueVarIndex = newLocal(Type.getType(String.class));
                        mv.visitLdcInsn("__none__");
                        mv.visitVarInsn(ASTORE, returnValueVarIndex);

                        // try { original }
                        mv.visitLabel(startTry);
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
                        String simpleMethodName = message.getSignature().substring(message.getSignature().lastIndexOf(".") + 1);
                        list.add(new SbNode(simpleMethodName));
                        list.add(new SbNode(",cost:"));
                        list.add(new SbNode(LLOAD, durationVarIndex));
                        list.add(new SbNode("ms,req:"));
                        list.add(new SbNode(ALOAD, paramsVarIndex));
                        list.add(new SbNode(",res:"));
                        list.add(new SbNode(ALOAD, returnValueVarIndex));
                        asmGenerateStringBuilder(mv, list);
                        mv.visitMethodInsn(INVOKESTATIC, "w/Global", "info", "(Ljava/lang/Object;)V", false);
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

                        /*-----------------------cost < minCost skip---------------------------------------------*/
                        mv.visitLabel(elseLabel);
                        mv.visitLabel(endLabel);
                        mv.visitLabel(endTry);

                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        mv.visitLabel(startCatch);
                        int exceptionIndex = newLocal(Type.getType(Throwable.class));
                        mv.visitVarInsn(Opcodes.ASTORE, exceptionIndex);
                        mv.visitVarInsn(Opcodes.ALOAD, exceptionIndex);
                        int exceptionStringIndex = newLocal(Type.getType(String.class));
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                        mv.visitVarInsn(Opcodes.ASTORE, exceptionStringIndex);

                        /*---------------------cost:     long end = System.currentTimeMillis(); long duration = end - start;-----------------*/
                        int durationVarIndex = asmCalculateCost(mv, startTimeVarIndex);
                        Label elseLabel = new Label();
                        Label endLabel = new Label();
                        mv.visitVarInsn(Opcodes.LLOAD, durationVarIndex);
                        mv.visitLdcInsn((long) minCost);
                        mv.visitInsn(LCMP);
                        // if duration < minCost goto elselabel
                        mv.visitJumpInsn(Opcodes.IFLT, elseLabel);

                        /*---------------------counter: if reach the limitation will remove the transformer----------------*/
                        mv.visitLdcInsn(uuid.toString());
                        mv.visitMethodInsn(INVOKESTATIC, "w/Global", "checkCountAndUnload", "(Ljava/lang/String;)V", false);

                        /*---------------------print: concat the final string, and w.Global.info-----------------*/
                        List<SbNode> list = new ArrayList<>();
                        list.add(new SbNode(message.getSignature()));
                        list.add(new SbNode(",cost:"));
                        list.add(new SbNode(LLOAD, durationVarIndex));
                        list.add(new SbNode("ms,req:"));
                        list.add(new SbNode(ALOAD, paramsVarIndex));
                        list.add(new SbNode(",throw:"));
                        list.add(new SbNode(ALOAD, exceptionStringIndex));
                        asmGenerateStringBuilder(mv, list);
                        mv.visitLdcInsn(traceId);
                        mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "fillCurThread", "(Ljava/lang/String;)V", false);
                        mv.visitMethodInsn(INVOKESTATIC, "w/Global", "info", "(Ljava/lang/Object;)V", false);
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

                        /*-----------------------cost < minCost skip---------------------------------------------*/
                        mv.visitLabel(elseLabel);
                        mv.visitLabel(endLabel);

                        mv.visitVarInsn(Opcodes.ALOAD, exceptionIndex);
                        mv.visitInsn(Opcodes.ATHROW);
                        mv.visitLabel(endCatch);
                        mv.visitTryCatchBlock(startTry, endTry, startCatch, "java/lang/Throwable");

                        effect.set(true);
                        super.visitMaxs(maxStack, maxLocals);
                    }

                };
            }
        }, ClassReader.EXPAND_FRAMES );
        byte[] result = classWriter.toByteArray();
        if (!effect.get()) {
            throw new IllegalArgumentException("Method not declared here.");
        }
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

}
