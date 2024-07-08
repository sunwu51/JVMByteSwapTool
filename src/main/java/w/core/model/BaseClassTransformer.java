package w.core.model;

import lombok.Getter;
import lombok.Setter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import w.Global;
import w.util.RequestUtils;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author Frank
 * @date 2023/12/21 23:45
 */
@Getter
@Setter
public abstract class BaseClassTransformer implements ClassFileTransformer {
    protected UUID uuid = UUID.randomUUID();

    protected String className;

    protected String traceId;

    protected int status;



    public abstract byte[] transform(byte[] origin) throws Exception;

    public abstract String desc();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        if (className == null) return origin;
        className = className.replace("/", ".");
        if (Objects.equals(this.className, className)) {
            try{
                byte[] r = transform(origin);
                Global.info(className + " transformer " + uuid +  " added success <(^-^)>");
                return r;
            } catch (Exception e) {
                Global.error(className + " transformer " + uuid + " added fail -(′д｀)-: ", e);
                // async to delete, because current thread holds the class lock
                CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
            }
        }
        return null;
    }

    public void clear() {

    }
    protected String paramTypesToDescriptor(List<String> paramTypes) {
        StringBuilder s = new StringBuilder();
        for (String paramType : paramTypes) {
            s.append(paramTypeToDescriptor(paramType));
        }
        return "(" + s + ")";
    }

    protected String paramTypeToDescriptor(String paramType) {
        if (paramType == null || paramType.isEmpty() || paramType.contains("<")) {
            throw new IllegalArgumentException("error type");
        }
        switch (paramType) {
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "boolean":
                return "Z";
            case "double":
                return "D";
            case "byte":
                return "B";
            case "short":
                return "S";
            case "char":
                return "C";
            default:
                if (paramType.endsWith("[]")) {
                    return "[" + paramTypeToDescriptor(paramType.substring(0, paramType.length() - 2));
                }
                return "L" + paramType.replace(".", "/") + ";";
        }
    }

    protected AbstractInsnNode loadVar(Type type, int index) {
        switch (type.getSort()) {
            case Type.INT:
            case Type.SHORT:
            case Type.BYTE:
            case Type.BOOLEAN:
            case Type.CHAR:
                return new VarInsnNode(ILOAD, index);
            case Type.FLOAT:
                return new VarInsnNode(FLOAD, index);
            case Type.DOUBLE:
                return new VarInsnNode(DLOAD, index);
            case Type.LONG:
                return new VarInsnNode(LLOAD, index);
            case Type.ARRAY:
            case Type.OBJECT:
                return new VarInsnNode(ALOAD, index);
            case Type.VOID:
                return new InsnNode(NOP);
            default:
                throw new RuntimeException("Unsupport type");
        }
    }

    protected List<AbstractInsnNode> storeVarWithDefaultValue(Type type, int index) {
        List<AbstractInsnNode> result = new ArrayList<>();
        switch (type.getSort()) {
            case Type.INT:
            case Type.SHORT:
            case Type.BYTE:
            case Type.BOOLEAN:
            case Type.CHAR:
                result.add(new InsnNode(ICONST_0));
                result.add(new VarInsnNode(ISTORE, index));
                return result;
            case Type.FLOAT:
                result.add(new InsnNode(FCONST_0));
                result.add(new VarInsnNode(FSTORE, index));
                return result;
            case Type.DOUBLE:
                result.add(new InsnNode(DCONST_0));
                result.add(new VarInsnNode(DSTORE, index));
                return result;
            case Type.LONG:
                result.add(new InsnNode(LCONST_0));
                result.add(new VarInsnNode(LSTORE, index));
                return result;
            case Type.ARRAY:
            case Type.OBJECT:
                result.add(new InsnNode(ACONST_NULL));
                result.add(new VarInsnNode(ASTORE, index));
                return result;
            case Type.VOID:
                return result;
            default:
                throw new RuntimeException("Unsupport type");
        }
    }
}