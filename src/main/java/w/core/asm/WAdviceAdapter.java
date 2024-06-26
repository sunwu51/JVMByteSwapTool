package w.core.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * @author Frank
 * @date 2024/6/22 19:33
 */
public class WAdviceAdapter extends AdviceAdapter {
    protected WAdviceAdapter(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor);
    }

    protected int asmStoreStartTime(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        int startTimeVarIndex = newLocal(Type.LONG_TYPE);
        mv.visitVarInsn(LSTORE, startTimeVarIndex);
        return startTimeVarIndex;
    }

    protected int asmCalculateCost(MethodVisitor mv, int startTimeVarIndex) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        mv.visitVarInsn(LLOAD, startTimeVarIndex);
        mv.visitInsn(LSUB);
        int durationVarIndex = newLocal(Type.LONG_TYPE);
        mv.visitVarInsn(LSTORE, durationVarIndex);
        return durationVarIndex;
    }

    protected int asmStoreParamsString(MethodVisitor mv, int printFormat) {
        loadArgArray();
        if (printFormat == 1) {
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false);
        } else if (printFormat == 2) {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toJson", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        }
        int paramsVarIndex = newLocal(Type.getType(String.class));
        mv.visitVarInsn(ASTORE, paramsVarIndex);
        return paramsVarIndex;
    }


    protected int asmSubCallStoreParamsString(MethodVisitor mv, int printFormat, String descriptor) {
        int _i = subCallParamsToArray(descriptor);
        mv.visitVarInsn(ALOAD, _i);
        if (printFormat == 1) {
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false);
        } else if (printFormat == 2) {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toJson", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        }
        int paramsVarIndex = newLocal(Type.getType(String.class));
        mv.visitVarInsn(ASTORE, paramsVarIndex);
        return paramsVarIndex;
    }

    /**
     * return value toString and store in local variable, return the local variable index
     *
     * It's very useful for enhancement like watch out-watch.
     * @param mv
     * @param descriptor
     * @return
     */
    protected int asmStoreRetString(MethodVisitor mv, String descriptor, int printFormat) {
        int returnValueVarIndex = newLocal(Type.getType(String.class));
        return asmStoreRetString(mv, descriptor, printFormat, returnValueVarIndex);
    }

    protected int asmStoreRetString(MethodVisitor mv, String descriptor, int printFormat, int returnValueVarIndex) {
        Type returnType = Type.getReturnType(descriptor);
        switch (returnType.getSort()) {
            case Type.ARRAY:
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false);
                break;
            case Type.DOUBLE:
            case Type.LONG:
                mv.visitInsn(DUP2);
                box(returnType);
                formatResult(printFormat);
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.INT:
            case Type.FLOAT:
            case Type.SHORT:
            case Type.BYTE:
                mv.visitInsn(DUP);
                box(returnType);
                formatResult(printFormat);
                break;
            case Type.OBJECT:
                mv.visitInsn(DUP);
                formatResult(printFormat);
                break;
            case Type.VOID:
            default:
                mv.visitLdcInsn("void");
        }
        mv.visitVarInsn(ASTORE, returnValueVarIndex);
        return returnValueVarIndex;
    }


    private void formatResult(int printFormat) {
        if (printFormat == 1) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        } else if (printFormat == 2) {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toJson",   "(Ljava/lang/Object;)Ljava/lang/String;", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        }
    }

    private int subCallParamsToArray(String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        int[] loads = new int[argumentTypes.length];
        int[] index = new int[argumentTypes.length];
        for (int i = argumentTypes.length - 1; i >= 0; i--) {
            switch (argumentTypes[i].getSort()) {
                case Type.LONG:
                    int li = newLocal(Type.LONG_TYPE);
                    mv.visitVarInsn(LSTORE, li);
                    index[i] = li; loads[i] = LLOAD;
                    break;
                case Type.DOUBLE:
                    int di = newLocal(Type.DOUBLE_TYPE);
                    mv.visitVarInsn(DSTORE, di);
                    index[i] = di;loads[i] = DLOAD;
                    break;
                case Type.BOOLEAN:
                    int zi = newLocal(Type.BOOLEAN_TYPE);
                    mv.visitVarInsn(ISTORE, zi);
                    index[i] = zi;loads[i] = ILOAD;
                    break;
                case Type.BYTE:
                    int bi = newLocal(Type.BYTE_TYPE);
                    mv.visitVarInsn(ISTORE, bi);
                    index[i] = bi;loads[i] = ILOAD;
                    break;
                case Type.CHAR:
                    int ci = newLocal(Type.CHAR_TYPE);
                    mv.visitVarInsn(ISTORE, ci);
                    index[i] = ci;loads[i] = ILOAD;
                    break;
                case Type.SHORT:
                    int si = newLocal(Type.SHORT_TYPE);
                    mv.visitVarInsn(ISTORE, si);
                    index[i] = si;loads[i] = ILOAD;
                    break;
                case Type.FLOAT:
                    int fi = newLocal(Type.FLOAT_TYPE);
                    mv.visitVarInsn(FSTORE, fi);
                    index[i] = fi;loads[i] = FLOAD;
                    break;
                case Type.INT:
                    int ii = newLocal(Type.INT_TYPE);
                    mv.visitVarInsn(ISTORE, ii);
                    index[i] = ii;loads[i] = ILOAD;
                    break;
                default:
                    int ai = newLocal(Type.getType(Object.class));
                    mv.visitVarInsn(ASTORE, ai);
                    index[i] = ai;loads[i] = ALOAD;
                    break;
            }
        }
        push(argumentTypes.length);
        newArray(Type.getObjectType("java/lang/Object"));
        for (int i = 0; i < index.length; i++) {
            dup();
            push(i);
            mv.visitVarInsn(loads[i], index[i]);
            box(argumentTypes[i]);
            arrayStore(Type.getObjectType("java/lang/Object"));
        }
        int result = newLocal(Type.getType(Object.class));
        mv.visitVarInsn(ASTORE, result);
        for (int i = 0; i < index.length; i++) {
            mv.visitVarInsn(loads[i], index[i]);
        }
        return result;
    }

    protected void asmGenerateStringBuilder(MethodVisitor mv, List<SbNode> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);

        for (SbNode subStringNode : list) {
            subStringNode.loadAndAppend(mv);
        }
    }
}
