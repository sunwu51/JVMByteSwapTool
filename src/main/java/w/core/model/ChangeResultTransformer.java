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
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import w.Global;
import w.core.Constants.Codes;
import w.core.asm.WAdviceAdapter;
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
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


    public static String toASMCode(byte[] bytecode, boolean debug) throws IOException {
        int flags = ClassReader.SKIP_DEBUG;

        if (debug) {
            flags = 0;
        }

        ClassReader cr = new ClassReader(new ByteArrayInputStream(bytecode));
        StringWriter sw = new StringWriter();
        cr.accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(sw)), flags);
        return sw.toString();
    }

    private byte[] changeResultByASM(byte[] origin) throws CompileException, IOException {

        ClassReader classReader = new ClassReader(origin);

        // Create a class writer to modify the class
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        String paramDes = paramTypesToDescriptor(paramTypes);

        // A container to collect the whole injection method info
        MethodNode methodNode = new MethodNode(ASM9);

        ClassReader newCr = new ClassReader(compileDynamicCodeBlock(message.getBody()));
        newCr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("test")) {
                    return methodNode;
                }
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);


        MethodNode newInstructionsNode = new MethodNode(Opcodes.ASM9);
        newInstructionsNode.visitMethodInsn(INVOKEVIRTUAL, "w/core/TestClass", "helloWrapper", "(Ljava/lang/String;)Ljava/lang/String;", false);



        InsnList instructions = methodNode.instructions;
        ListIterator<AbstractInsnNode> iterator = instructions.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insnNode = iterator.next();
            if (insnNode instanceof MethodInsnNode) {
                MethodInsnNode methodInsnNode = (MethodInsnNode) insnNode;
                if (methodInsnNode.owner.equals("w/core/TestClass") && methodInsnNode.name.equals("hello") && methodInsnNode.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                    // Replace the target method call with new instructions
                    instructions.insert(methodInsnNode, newInstructionsNode.instructions);
                    instructions.remove(methodInsnNode);
                }
            }
        }



        // Accept the visitor to modify the class
        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals(method) && descriptor.startsWith(paramDes)) {
//                    return new MethodVisitor(ASM9, mv) {
//                        @Override
//                        public void visitCode() {
////                            AbstractInsnNode[] arr = methodNode.instructions.toArray();
//                            methodNode.instructions.remove(methodNode.instructions.getLast());
//                            methodNode.accept(mv);
//                            super.visitCode();
//                        }
//                    };
////
                    return new WAdviceAdapter(api, mv, access, name, descriptor){
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (opcode == INVOKEVIRTUAL &&
                                    (Objects.equals(owner.replace("/", "."), innerClassName) ||"*".equals(innerClassName))
                                    && name.equals(innerMethod)) {
                                // Insert the compiled code before invoke
                                methodNode.instructions.remove(methodNode.instructions.getLast());
                                methodNode.accept(mv);
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
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
