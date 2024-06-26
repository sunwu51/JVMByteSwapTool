package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import org.codehaus.commons.compiler.CompileException;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import w.Global;
import w.core.WCompiler;
import w.core.constant.Codes;
import w.web.message.ChangeResultMessage;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class ChangeResultTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient ChangeResultMessage message;

    String method;

    List<String> paramTypes;

    String innerClassName;

    String innerMethod;

    int mode;

    public ChangeResultTransformer(ChangeResultMessage message) {
        this.className = message.getClassName();
        this.method = message.getMethod();
        this.paramTypes = message.getParamTypes();
        this.innerMethod = message.getInnerMethod();
        this.innerClassName = message.getInnerClassName();
        this.message = message;
        this.traceId = message.getId();
        this.mode = message.getMode();
    }

    @Override
    public byte[] transform(Class<?> claz, byte[] origin) throws Exception {
        byte[] result = null;
        if (mode == Codes.changeResultModeUseJavassist) {
            // use javassist $_=xxx to change result
            result = changeResultByJavassist(origin);
        } else if (mode == Codes.changeResultModeUseASM) {
            // use asm, will create a new dynamic class with a method contains the code
            // then the origin code will be replaced by the dynamic method
            result = changeResultByASM(origin);
        }
        status = 1;

        new FileOutputStream("T.class").write(result);
        return result;
    }


    private byte[] changeResultByJavassist(byte[] origin) throws Exception {
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
                declaredMethod.instrument(new ExprEditor() {
                    public void edit(MethodCall m) throws CannotCompileException {
                        if (m.getMethodName().equals(innerMethod)) {
                            if (Objects.equals(innerClassName, "*") || Objects.equals(innerClassName, m.getClassName())) {
                                m.replace("{"+message.getBody()+"}");
                            }
                        }
                    }
                });
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


    private byte[] changeResultByASM(byte[] origin) throws CompileException, IOException {

        String paramDes = paramTypesToDescriptor(paramTypes);
        // A container to collect the outer method insn
        MethodNode outerNode = new MethodNode(ASM9);

        // A container to collect the injection method insn
        MethodNode replacementNode = new MethodNode(ASM9);

        ClassReader cr = new ClassReader(origin);
        ClassReader rcr = new ClassReader(WCompiler.compileDynamicCodeBlock(message.getBody()));
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals(method) && descriptor.startsWith(paramDes)) {
                    return outerNode;
                }
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);

        final Type[] returnType = {null};

        ClassNode replaceClassNode = new ClassNode();
        rcr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // fixed signature: public static XX replace(){}
                if (name.equals("replace") && descriptor.startsWith("()") && (access & ACC_STATIC) > 0) {
                    returnType[0] = Type.getReturnType(descriptor);
                    return replacementNode;
                }
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);

        if (replacementNode.instructions.size() == 0 || outerNode.instructions.size() == 0) {
            throw new IllegalArgumentException("Param error, method not exist or different name");
        }

        // replace the innerMethod with the replacement
        InsnList list = new InsnList();
        for (AbstractInsnNode instruction : outerNode.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode mnode = (MethodInsnNode) instruction;
                if (mnode.name.equals(innerMethod) && (
                        mnode.owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                        && returnType[0].equals(Type.getReturnType(mnode.desc))
                ) {
                    Type[] types = Type.getArgumentTypes(mnode.desc);
                    for (int i = types.length - 1; i >= 0; i--) {
                        if (types[i] == Type.DOUBLE_TYPE || types[i] == Type.LONG_TYPE) {
                            list.add(new InsnNode(POP2));
                        } else {
                            list.add(new InsnNode(POP));
                        }
                    }
                    switch (mnode.getOpcode()) {
                        case INVOKEVIRTUAL:
                        case INVOKEINTERFACE:
                        case INVOKESPECIAL:
                            list.add(new InsnNode(POP));
                            break;
                        case INVOKESTATIC:
                            break;
                        default:
                            throw new IllegalStateException("Not supported method invocation type: " + mnode.getOpcode());
                    }
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LineNumberNode) continue;
                        if (repInsn.getOpcode() >= IRETURN && repInsn.getOpcode() <= RETURN) continue;

                        list.add(repInsn);
                    }
                    continue;
                }
            }
            list.add(instruction);
        }

        // Create a class writer to modify the class
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals(method) && descriptor.startsWith(paramDes)) {
                    return new MethodVisitor(ASM9, mv) {
                        @Override
                        public void visitCode() {
                            list.accept(mv);
                            replacementNode.tryCatchBlocks.forEach(a->{
                                a.accept(mv);
                            });
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] result = classWriter.toByteArray();
        new FileOutputStream("T.class").write(result);
        return result;
    }

    public boolean equals(Object other) {
        if (other instanceof ChangeResultTransformer) {
            return this.uuid.equals(((ChangeResultTransformer) other).getUuid());
        }
        return false;
    }
    @Override
    public String desc() {
        return "ChangeResult_" + getClassName() + "#" + method + " " + paramTypes;
    }
}
