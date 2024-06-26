package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import lombok.Data;

import org.codehaus.commons.compiler.CompileException;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import w.Global;
import w.core.WCompiler;
import w.core.constant.Codes;
import w.web.message.ChangeBodyMessage;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.objectweb.asm.Opcodes.*;

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

        boolean effect = false;
        String paramDes = paramTypesToDescriptor(paramTypes);

        // A container to collect the outer method insn
        MethodNode outerNode = new MethodNode(ASM9);

        ClassReader cr = new ClassReader(origin);
        ClassReader rcr = null;

        ClassNode targetClassNode = new ClassNode();
        cr.accept(targetClassNode, ClassReader.EXPAND_FRAMES);

        for (MethodNode mn : targetClassNode.methods) {
            // find target method
            if (mn.name.equals(method) && mn.desc.startsWith(paramDes)) {
                // compile replacement
                rcr = compileReplacement(mn);
                ClassNode replacementClassNode = new ClassNode();
                rcr.accept(replacementClassNode, 0);
                for (MethodNode rmn : replacementClassNode.methods) {
                    if (rmn.name.equals(method) && rmn.desc.startsWith(paramDes)) {
                        mn.instructions = rmn.instructions;
                        mn.tryCatchBlocks = rmn.tryCatchBlocks;
                        mn.localVariables = rmn.localVariables;
                        effect = true;
                        break;
                    }
                }


            }
        }
        if (!effect) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        targetClassNode.accept(classWriter);
        byte[] result = classWriter.toByteArray();
        new FileOutputStream("T.class").write(result);
        return result;
    }

    private byte[] changeBodyByASM2(byte[] origin) throws Exception {
        String parameterDes = paramTypesToDescriptor(paramTypes);
        AtomicBoolean effect = new AtomicBoolean();
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
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
                ClassReader newMethodReader = null;

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
        new FileOutputStream("T.class").write(result);
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

    private ClassReader compileReplacement(MethodNode mn) {
        try {
            String descriptor = mn.desc;
            List<String> exceptions = mn.exceptions;
            StringBuilder m = new StringBuilder();
            m.append(Type.getReturnType(descriptor).getClassName()).append(" ").append(method).append("(");
            Type[] params = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < params.length; i++) {
                if (i != 0) m.append(", ");
                m.append(params[i].getClassName()).append(" ").append("$").append(i + 1);
            }
            m.append(")");

            if (exceptions != null && !exceptions.isEmpty()) {
                m.append("throws ");
                for (int i = 0; i < exceptions.size(); i++) {
                    if (i != 0) m.append(",");
                    m.append(exceptions.get(i).replace("/", "."));
                }
            }

            m.append(message.getBody());

            return new ClassReader(WCompiler.compileMethod(className, m.toString()));
        }  catch (CompileException e) {
            throw new IllegalArgumentException("Source code compile error", e);
        }
    }


}
/**
 * String paramDes = paramTypesToDescriptor(paramTypes);
 *         // A container to collect the outer method insn
 *         MethodNode outerNode = new MethodNode(ASM9);
 *
 *         // A container to collect the injection method insn
 *         MethodNode replacementNode = new MethodNode(ASM9);
 *
 *         ClassReader cr = new ClassReader(origin);
 *         ClassReader[] rcr = new ClassReader[1];
 *         cr.accept(new ClassVisitor(Opcodes.ASM9) {
 *             @Override
 *             public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
 *                 if (name.equals(method) && descriptor.startsWith(paramDes)) {
 *                     try {
 *                         StringBuilder m = new StringBuilder();
 *                         if ((access & ACC_PUBLIC) > 0) {
 *                             m.append("public ");
 *                         } else if ((access & ACC_PRIVATE) > 0) {
 *                             m.append("private ");
 *                         } else if ((access & ACC_PROTECTED) > 0) {
 *                             m.append("protected ");
 *                         }
 *
 *                         if ((access & ACC_STATIC) > 0) {
 *                             m.append("static ");
 *                         }
 *
 *                         m.append(Type.getReturnType(descriptor).getClassName()).append(" ").append(method).append("(");
 *                         Type[] params = Type.getArgumentTypes(descriptor);
 *                         for (int i = 0; i < params.length; i++) {
 *                             if (i != 0) m.append(", ");
 *                             m.append(params[i].getClassName()).append(" ").append("$").append(i + 1);
 *                         }
 *                         m.append(")");
 *
 *                         if (exceptions != null && exceptions.length > 0) {
 *                             m.append("throws ");
 *                             for (int i = 0; i < exceptions.length; i ++) {
 *                                 if (i!=0) m.append(",");
 *                                 m.append(exceptions[i].replace("/", "."));
 *                             }
 *                         }
 *
 *                         m.append(message.getBody());
 *
 *                         rcr[0] = new ClassReader(compileMethod(m.toString()));
 *                     } catch (CompileException e) {
 *                         throw new IllegalArgumentException("Source code compile error", e);
 *                     }
 *                     return outerNode;
 *                 }
 *                 return null;
 *             }
 *         }, ClassReader.EXPAND_FRAMES);
 *
 *         ClassNode classNode = new ClassNode();
 *         rcr[0].accept(classNode, 0);
 *
 *         MethodNode sourceMethod = null;
 *         for (MethodNode m : classNode.methods) {
 *             if (m.name.equals(method) && m.desc.startsWith(paramDes)) {
 *                 sourceMethod = m;
 *                 break;
 *             }
 *         }
 *         if (sourceMethod != null) {
 *             ClassNode targetClassNode = new ClassNode();
 *             cr.accept(targetClassNode, ClassReader.EXPAND_FRAMES);
 *
 *             // 查找目标方法并替换其内容
 *             for (MethodNode m : targetClassNode.methods) {
 *                 if (m.name.equals(method) && m.desc.startsWith(paramDes)) {
 *                     m.instructions = sourceMethod.instructions;
 *                     m.tryCatchBlocks = sourceMethod.tryCatchBlocks;
 *                     m.localVariables = sourceMethod.localVariables;
 *                     break;
 *                 }
 *             }
 *
 *             // 写出修改后的类
 *             ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
 *             targetClassNode.accept(classWriter);
 *             byte[] result = classWriter.toByteArray();
 *             new FileOutputStream("T.class").write(result);
 *             return result;
 *         }
 *
 *
 *         return null;
 */