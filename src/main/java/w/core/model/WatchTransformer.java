package w.core.model;

import javassist.*;
import lombok.Data;
import org.objectweb.asm.*;
import w.Global;
import w.core.asm.SbNode;
import w.core.asm.WAdviceAdapter;
import w.util.RequestUtils;
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
    public byte[] transform(byte[] origin) throws Exception {
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

                    private int returnValueVarIndex = -1;

                    private int exceptionStringIndex = -1;


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
                        paramsVarIndex = asmStoreParamsString(mv, printFormat);
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
                            returnValueVarIndex = asmStoreRetString(mv, descriptor, printFormat);
                            postProcess(false);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        mv.visitLabel(endTry);
                        mv.visitLabel(startCatch);
                        int exceptionIndex = newLocal(Type.getType(Throwable.class));
                        mv.visitVarInsn(Opcodes.ASTORE, exceptionIndex);
                        mv.visitVarInsn(Opcodes.ALOAD, exceptionIndex);
                        exceptionStringIndex = newLocal(Type.getType(String.class));
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                        mv.visitVarInsn(Opcodes.ASTORE, exceptionStringIndex);

                        postProcess(true);
                        mv.visitVarInsn(Opcodes.ALOAD, exceptionIndex);
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
                        mv.visitMethodInsn(INVOKESTATIC, "w/core/asm/Tool", "watchPostProcess", "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
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
