package w.core.asm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.List;

/**
 * @author Frank
 * @date 2024/6/22 19:33
 */
public class WAdviceAdapter extends AdviceAdapter {
    protected WAdviceAdapter(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor);
    }

    /**
     * get current milliseconds
     *
     *  long startTime = System.currentTimeMillis();
     *
     * @param mv
     * @return the start time variable index
     */
    protected int asmStoreStartTime(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        int startTimeVarIndex = newLocal(Type.LONG_TYPE);
        mv.visitVarInsn(LSTORE, startTimeVarIndex);
        return startTimeVarIndex;
    }

    /**
     * calculate the cost, return cost variable index
     *
     *  long duration = System.currentTimeMillis() - startTime;
     *
     * @param mv
     * @param startTimeVarIndex
     * @return the duration time variable index
     */
    protected int asmCalculateCost(MethodVisitor mv, int startTimeVarIndex) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        mv.visitVarInsn(LLOAD, startTimeVarIndex);
        mv.visitInsn(LSUB);
        int durationVarIndex = newLocal(Type.LONG_TYPE);
        mv.visitVarInsn(LSTORE, durationVarIndex);
        return durationVarIndex;
    }

    /**
     * params to string and return the string variable index
     *
     *  Object[] array = new Object[] {arg1, arg2, arg3...};
     *  String paramsVar = null;
     *  if (printFormat == 1)  paramsVar =  Arrays.toString(array);
     *  else if (printFormat == 2) paramsVar = Global.toJson(array);
     *  else paramsVar = Global.toString(array);
     *
     * @param mv
     * @param printFormat 1 toString 2 toJson 3 toPrettyString
     * @return the paramsVar index
     */
    protected int asmStoreParamsString(MethodVisitor mv, int printFormat) {
        return asmStoreParamsString(mv, printFormat, -1);
    }

    protected int asmStoreParamsString(MethodVisitor mv, int printFormat, int depthForJson) {
        int paramsArrayIndex = asmStoreArgArray();
        return asmStoreObjectString(paramsArrayIndex, printFormat, depthForJson, true);
    }

    protected int asmStoreArgArray() {
        loadArgArray();
        int paramsArrayIndex = newLocal(Type.getType(Object[].class));
        mv.visitVarInsn(ASTORE, paramsArrayIndex);
        return paramsArrayIndex;
    }

    protected int asmStoreObjectString(int objectVarIndex, int printFormat, int depthForJson) {
        return asmStoreObjectString(objectVarIndex, printFormat, depthForJson, false);
    }

    protected int asmStoreObjectString(int objectVarIndex, int printFormat, int depthForJson, boolean objectArray) {
        mv.visitVarInsn(ALOAD, objectVarIndex);
        if (printFormat == 1) {
            if (objectArray) {
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            }
        } else if (printFormat == 2) {
            push(depthForJson);
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toJson", "(Ljava/lang/Object;I)Ljava/lang/String;", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        }
        int paramsVarIndex = newLocal(Type.getType(String.class));
        mv.visitVarInsn(ASTORE, paramsVarIndex);
        return paramsVarIndex;
    }


    /**
     * sub method params to string and return the string variable index, similar to asmStoreParamsString
     * but for the sub method
     * @param mv
     * @param printFormat
     * @param descriptor
     * @return
     */
    protected int asmSubCallStoreParamsString(MethodVisitor mv, int printFormat, String descriptor) {
        return asmSubCallStoreParamsString(mv, printFormat, -1, descriptor);
    }

    protected int asmSubCallStoreParamsString(MethodVisitor mv, int printFormat, int depthForJson, String descriptor) {
        int paramsArrayIndex = asmSubCallStoreParamsArray(descriptor);
        return asmStoreObjectString(paramsArrayIndex, printFormat, depthForJson, true);
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

    /**
     * return value toString and store in local variable
     * @param mv
     * @param descriptor
     * @param printFormat
     * @param returnValueVarIndex given local variable index
     * @return
     */
    protected int asmStoreRetString(MethodVisitor mv, String descriptor, int printFormat, int returnValueVarIndex) {
        return asmStoreRetStringWithDepth(mv, descriptor, printFormat, -1, returnValueVarIndex);
    }

    protected int asmStoreRetStringWithDepth(MethodVisitor mv, String descriptor, int printFormat, int depthForJson, int returnValueVarIndex) {
        Type returnType = Type.getReturnType(descriptor);
        switch (returnType.getSort()) {
            case Type.DOUBLE:
            case Type.LONG:
                mv.visitInsn(DUP2);
                box(returnType);
                formatResult(printFormat, depthForJson);
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.INT:
            case Type.FLOAT:
            case Type.SHORT:
            case Type.BYTE:
                mv.visitInsn(DUP);
                box(returnType);
                formatResult(printFormat, depthForJson);
                break;
            case Type.ARRAY:
            case Type.OBJECT:
                mv.visitInsn(DUP);
                formatResult(printFormat, depthForJson);
                break;
            case Type.VOID:
            default:
                mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitVarInsn(ASTORE, returnValueVarIndex);
        return returnValueVarIndex;
    }

    private void formatResult(int printFormat) {
        formatResult(printFormat, -1);
    }

    private void formatResult(int printFormat, int depthForJson) {
        if (printFormat == 1) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        } else {
            push(depthForJson);
            mv.visitMethodInsn(INVOKESTATIC, "w/Global", "toJson",   "(Ljava/lang/Object;I)Ljava/lang/String;", false);
        }
    }

    protected int asmStoreRetObject(MethodVisitor mv, String descriptor) {
        int returnValueVarIndex = newLocal(Type.getType(Object.class));
        Type returnType = Type.getReturnType(descriptor);
        switch (returnType.getSort()) {
            case Type.DOUBLE:
            case Type.LONG:
                mv.visitInsn(DUP2);
                box(returnType);
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.INT:
            case Type.FLOAT:
            case Type.SHORT:
            case Type.BYTE:
                mv.visitInsn(DUP);
                box(returnType);
                break;
            case Type.ARRAY:
            case Type.OBJECT:
                mv.visitInsn(DUP);
                break;
            case Type.VOID:
            default:
                mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitVarInsn(ASTORE, returnValueVarIndex);
        return returnValueVarIndex;
    }

    /**
     * similar process with loadArgArray, but for sub method params
     * @param descriptor
     * @return
     */
    protected int asmSubCallStoreParamsArray(String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        int[] loads = new int[argumentTypes.length];
        int[] index = new int[argumentTypes.length];
        for (int i = argumentTypes.length - 1; i >= 0; i--) {
            switch (argumentTypes[i].getSort()) {
                case Type.LONG:
                    int li = newLocal(Type.LONG_TYPE);
                    mv.visitVarInsn(LSTORE, li);
                    index[i] = li;
                    loads[i] = LLOAD;
                    break;
                case Type.DOUBLE:
                    int di = newLocal(Type.DOUBLE_TYPE);
                    mv.visitVarInsn(DSTORE, di);
                    index[i] = di;
                    loads[i] = DLOAD;
                    break;
                case Type.BOOLEAN:
                    int zi = newLocal(Type.BOOLEAN_TYPE);
                    mv.visitVarInsn(ISTORE, zi);
                    index[i] = zi;
                    loads[i] = ILOAD;
                    break;
                case Type.BYTE:
                    int bi = newLocal(Type.BYTE_TYPE);
                    mv.visitVarInsn(ISTORE, bi);
                    index[i] = bi;
                    loads[i] = ILOAD;
                    break;
                case Type.CHAR:
                    int ci = newLocal(Type.CHAR_TYPE);
                    mv.visitVarInsn(ISTORE, ci);
                    index[i] = ci;
                    loads[i] = ILOAD;
                    break;
                case Type.SHORT:
                    int si = newLocal(Type.SHORT_TYPE);
                    mv.visitVarInsn(ISTORE, si);
                    index[i] = si;
                    loads[i] = ILOAD;
                    break;
                case Type.FLOAT:
                    int fi = newLocal(Type.FLOAT_TYPE);
                    mv.visitVarInsn(FSTORE, fi);
                    index[i] = fi;
                    loads[i] = FLOAD;
                    break;
                case Type.INT:
                    int ii = newLocal(Type.INT_TYPE);
                    mv.visitVarInsn(ISTORE, ii);
                    index[i] = ii;
                    loads[i] = ILOAD;
                    break;
                default:
                    int ai = newLocal(Type.getType(Object.class));
                    mv.visitVarInsn(ASTORE, ai);
                    index[i] = ai;
                    loads[i] = ALOAD;
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

    /**
     * generate StringBuilder and append method, after method, the stringBuilder address will at the top of stack
     * @param mv
     * @param list
     */
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
