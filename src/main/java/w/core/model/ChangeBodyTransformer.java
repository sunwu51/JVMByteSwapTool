package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import lombok.Data;
import net.bytebuddy.jar.asm.*;
import w.Global;
import w.core.Constants.Codes;
import w.web.message.ChangeBodyMessage;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
        if (mode == Codes.changeBodyModeUseJavassist) {
            // use javassist, message.body is the method body, a code block starts with { ends with }
            result = changeBodyByJavassist(origin);
        } else if (mode == Codes.changeBodyModeUseASM) {
            // use asm, message.body is the whole method including signature, like `public void hi {}`
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
        ClassReader newMethodReader = new ClassReader(compileMethod(message.getBody()));
        String parameterDes = paramTypesToDescriptor(paramTypes);
        AtomicBoolean effect = new AtomicBoolean();
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);
        classReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            private int access = -10000;

            private String descriptor;

            private String signature;

            private String[] exceptions;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // remove the original method
                if (name.equals(method) && descriptor.startsWith(parameterDes)) {
                    this.access = access;
                    this.descriptor = descriptor;
                    this.signature = signature;
                    this.exceptions = exceptions;
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                newMethodReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
                    @Override
                    public MethodVisitor visitMethod(int _access, String _name, String _descriptor, String _signature, String[] _exceptions) {
                        if (_name.equals(method) && _descriptor.startsWith(parameterDes)) {
                            if (_access != access || !_descriptor.equals(descriptor) || !Objects.equals(_signature, signature) || !stringArrEquals(_exceptions, exceptions)) {
                                throw new IllegalStateException("Method signature not same with original method");
                            }
                            effect.set(true);
                            // append new method
                            return classWriter.visitMethod(_access, _name, _descriptor, _signature, _exceptions);
                        }
                        return null;
                    }
                }, ClassReader.EXPAND_FRAMES);
                super.visitEnd();
            }
        }, ClassReader.EXPAND_FRAMES);

        byte[] result = classWriter.toByteArray();
        if (!effect.get()) {
            throw new IllegalArgumentException("Method not declared here");
        }
        return result;
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


}
