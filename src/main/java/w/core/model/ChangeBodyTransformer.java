package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import lombok.Data;
import net.bytebuddy.jar.asm.*;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import w.Global;
import w.web.message.ChangeBodyMessage;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class ChangeBodyTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient ChangeBodyMessage message;

    String method;

    List<String> paramTypes;

    int mode;

    public ChangeBodyTransformer(ChangeBodyMessage message) {
        this.className = message.getClassName();
        this.method = message.getMethod();
        this.message = message;
        this.traceId = message.getId();
        this.paramTypes = message.getParamTypes();
        this.mode = message.getMode();
    }

    @Override
    public byte[] transform(Class<?> claz, byte[] origin) throws Exception {
        byte[] result = null;
        if (mode == 0) {
            result = changeBodyByJavassist(origin);
        } else if (mode == 1) {
            result = changeBodyByASM(origin);
        }
        new FileOutputStream("T.class").write(result);
        status = 1;
        return result;
    }

    private byte[] changeBodyByJavassist(byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        boolean effect = false;
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method) &&
                    Arrays.equals(paramTypes.toArray(new String[0]),
                            Arrays.stream(declaredMethod.getParameterTypes()).map(CtClass::getName).toArray())
            ) {
                if ((declaredMethod.getModifiers() & Modifier.ABSTRACT) != 0) {
                    throw new IllegalArgumentException("Cannot change abstract method.");
                }
                if ((declaredMethod.getModifiers() & Modifier.NATIVE) != 0) {
                    throw new IllegalArgumentException("Cannot change native method.");
                }
                declaredMethod.setBody(message.getBody());
                effect = true;
            }
        }
        if (!effect) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        byte[] result = ctClass.toBytecode();
        ctClass.detach();
        return result;
    }


    private byte[] changeBodyByASM(byte[] origin) throws Exception {
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);
        classReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals(method)) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                ClassReader newMethodReader = null;
                try {
                    newMethodReader = new ClassReader(compile(message.getBody()));
                } catch (CompileException e) {
                    throw new RuntimeException(e);
                }
                newMethodReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
                    @Override
                    public MethodVisitor visitMethod(int _access, String _name, String _descriptor, String _signature, String[] _exceptions) {
                        if (method.equals(_name)) {
                            return classWriter.visitMethod(_access, _name, _descriptor, _signature, _exceptions);
                        }
                        return null;
                    }
                }, ClassReader.EXPAND_FRAMES);
                super.visitEnd();
            }
        }, ClassReader.EXPAND_FRAMES);

        return classWriter.toByteArray();
    }

    private byte[] compile(String methodBody) throws CompileException {
        SimpleCompiler compiler = new SimpleCompiler();
        String packageName = className.substring(0, className.lastIndexOf("."));
        String simpleClassName = className.substring(className.lastIndexOf(".") +1);
        compiler.cook("package " + packageName +";\n import java.util.*;\n public class " + simpleClassName + " { public String " + method + "(String a) {" + methodBody + "} }");
        return compiler.getBytecodes().get(className);
    }


    public boolean equals(Object other) {
        if (other instanceof ChangeBodyTransformer) {
            return this.uuid.equals(((ChangeBodyTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "ChangeBody_" + getClassName() + "#" + method + " " + paramTypes;
    }

    private static boolean stringArrEquals(String[] a1, String[] a2) {
        if (a1 == null && a2 == null) { return  true;}
        if (a1 == null || a2 == null) { return false; }
        return Arrays.toString(a1).equals(Arrays.toString(a2));
    }

    private String paramTypesToDescriptor(List<String> paramTypes) {
        try {
            StringBuilder s = new StringBuilder();
            for (String paramType : paramTypes) {
                s.append(paramTypeToDescriptor(paramType));
            }
            return "(" + s + ")";
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String paramTypeToDescriptor(String paramType) {
        if (paramType == null || paramType.length() == 0 || paramType.contains("<")) {
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
                return "L" + paramType.replace(".", "/") + ";"
        }
    }
}
