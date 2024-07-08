package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fi.iki.elonen.NanoHTTPD;
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
import w.core.compiler.WCompiler;
import w.core.constant.Codes;
import w.web.message.ChangeResultMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND;
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
    public byte[] transform(byte[] origin) throws Exception {
        byte[] result = null;
        if (mode == Codes.changeResultModeUseJavassist) {
            // use javassist $_=xxx to change result
            result = changeResultByJavassist(origin);
        } else if (mode == Codes.changeResultModeUseASM) {
            // use asm, will create a new dynamic class with a method contains the code
            // then the origin code will be replaced by the dynamic method
            result = changeResultByASM(origin);
        }
        new FileOutputStream("T.class").write(result);

        status = 1;
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

        ClassReader cr = new ClassReader(origin);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals(method) && descriptor.startsWith(paramDes)) {
                    return outerNode;
                }
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);


        MethodNode replacementNode = null;
        String desc = null;
        // replace the innerMethod with the replacement
        InsnList list = new InsnList();
        int curMaxLocals = outerNode.maxLocals;
        for (AbstractInsnNode instruction : outerNode.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode mnode = (MethodInsnNode) instruction;
                if (mnode.name.equals(innerMethod) && (
                        mnode.owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                ) {
                    if (desc == null || desc.equals(mnode.desc)) {
                        desc = mnode.desc;
                    } else {
                        throw new IllegalStateException("Matched method descriptor more than once");
                    }
//                    Type[] types = Type.getArgumentTypes(mnode.desc);
//                    for (int i = types.length - 1; i >= 0; i--) {
//                        if (types[i] == Type.DOUBLE_TYPE || types[i] == Type.LONG_TYPE) {
//                            list.add(new InsnNode(POP2));
//                        } else {
//                            list.add(new InsnNode(POP));
//                        }
//                    }
//                    switch (mnode.getOpcode()) {
//                        case INVOKEVIRTUAL:
//                        case INVOKEINTERFACE:
//                        case INVOKESPECIAL:
//                            list.add(new InsnNode(POP));
//                            break;
//                        case INVOKESTATIC:
//                            break;
//                        default:
//                            throw new IllegalStateException("Not supported method invocation type: " + mnode.getOpcode());
//                    }
                    boolean isStatic = (mnode.getOpcode() & ACC_STATIC) > 0;
                    replacementNode = replacementNode == null ?
                            getReplacementMethodNode(isStatic, mnode.owner, mnode.desc) : replacementNode;

                    transArgsToVars(replacementNode.desc, curMaxLocals, list);
//
//                    if (isStatic) {
//                        transArgsToVars(replacementNode.desc, curMaxLocals, list);
//                    } else {
//                        transArgsToVars(replacementNode.desc, ++curMaxLocals, list);
//                        list.add(new VarInsnNode(ASTORE, curMaxLocals - 1)); // this
//                    }
                    int resVarIndex = curMaxLocals + Type.getArgumentCount(replacementNode.desc) - 1;


                    Map<LabelNode, LabelNode> labels = new HashMap<>();
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LabelNode) labels.put((LabelNode) repInsn, new LabelNode());
                    }
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LineNumberNode || repInsn.getOpcode() == RETURN) continue;
                        if (repInsn instanceof LabelNode) {
                            LabelNode t = labels.get(repInsn);
                            list.add(t);
                            continue;
                        }
                        if (repInsn instanceof VarInsnNode) {
                            VarInsnNode newInsn = (VarInsnNode)repInsn.clone(labels);
                            newInsn.var += curMaxLocals - 1;
                            list.add(newInsn);
                            continue;
                        }
                        if (repInsn instanceof IincInsnNode) {
                            IincInsnNode newInsn = (IincInsnNode)repInsn.clone(labels);
                            newInsn.var += curMaxLocals - 1;
                            list.add(newInsn);
                            continue;
                        }
                        list.add(repInsn.clone(labels));
                    }
                    list.add(new VarInsnNode(DLOAD, resVarIndex));
                    curMaxLocals +=  replacementNode.maxLocals;
                    continue;
                }
            }
            list.add(instruction);
        }
        final MethodNode rnode = replacementNode;

        if (rnode == null) {
            throw new IllegalArgumentException("Inner method not found");
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
                            if (rnode.tryCatchBlocks != null) {
                                rnode.tryCatchBlocks.forEach(a->{
                                    a.accept(mv);
                                });
                            }
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] result = classWriter.toByteArray();
        return result;
    }

    private static byte[] compile(boolean staticMethod, String owner, String desc, String body) throws CompileException, IOException {
        String template = getTemplate();
        Type[] paramTypes = Type.getArgumentTypes(desc);
        Type returnType = Type.getReturnType(desc);
        List<String> paramClassNames = Arrays.stream(paramTypes).map(Type::getClassName).collect(Collectors.toList());

        StringBuilder fields_placeholder = new StringBuilder();
        StringBuilder args_placeholder = new StringBuilder();
        StringBuilder args_process_placeholder = new StringBuilder();

        String className = owner.replace("/", ".");
        String packageName = className.substring(0, className.lastIndexOf("."));
        if (!staticMethod) {
            args_placeholder.append(owner.replace("/", ".")).append(" $0,");
        }
        for (int i = 0; i < paramClassNames.size(); i++) {
            String paramClassName = paramClassNames.get(i);
            args_placeholder.append(String.format("%s $%d,", paramClassName, i + 1));
        }
        if (returnType == Type.VOID_TYPE) {
            args_placeholder.append("Object $_");
        } else {
            args_placeholder.append(returnType.getClassName()).append(" $_\n");
        }
        if (!args_placeholder.toString().isEmpty() && args_placeholder.toString().endsWith(",")) {
            args_placeholder.delete(args_placeholder.length() - 1, args_placeholder.length());
        }

        String sourceCode = template.replace("{{fields_placeholder}}", fields_placeholder)
                .replace("{{args_placeholder}}", args_placeholder)
                .replace("{{body_placeholder}}", body)
                .replace("{{package_placeholder}}", "package " + packageName + ";");
                ;
        ;

        byte[] res = WCompiler.compileWholeClass(sourceCode);
        return res;
    }

    public static void main(String[] args) throws CompileException, IOException {
        compile(false, "w/Global","(ILjava/lang/String;JLjava/lang/Object;)D", "{$_ = $1+$2.length()+$3 + $4.hashCode();}");
    }

    private static String getTemplate() {
        String sourceCode = null;
        try (InputStream in = ChangeResultMessage.class.getResourceAsStream("/InlineWrapper.java");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            sourceCode = reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("Source file not exist:");
        }
        return sourceCode;
    }


    private MethodNode getReplacementMethodNode(boolean isStatic, String owner, String descriptor) throws CompileException, IOException {
        MethodNode replacementNode = new MethodNode(ASM9);
        ClassReader rcr = new ClassReader(compile(isStatic, owner, descriptor, message.getBody()));
        // A container to collect the injection method insn
        rcr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("replace")) {
                    replacementNode.desc = descriptor;
                    return replacementNode;
                }
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);

        return replacementNode;
    }

    private static void transArgsToVars(String desc, int curMaxLocals, InsnList enList) {
        Type[] argumentTypes = Type.getArgumentTypes(desc);
        for (int i = argumentTypes.length - 2; i >= 0; i--) {
            Type t = argumentTypes[i];
            switch (t.getSort()) {
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    enList.add(new VarInsnNode(ISTORE, curMaxLocals + i));
                    break;
                case Type.FLOAT:
                    enList.add(new VarInsnNode(FSTORE, curMaxLocals + i));
                    break;
                case Type.DOUBLE:
                    enList.add(new VarInsnNode(DSTORE, curMaxLocals + i));
                    break;
                case Type.LONG:
                    enList.add(new VarInsnNode(LSTORE, curMaxLocals + i));
                    break;
                case Type.ARRAY:
                case Type.OBJECT:
                    enList.add(new VarInsnNode(ASTORE, curMaxLocals + i));
                    break;
                default:
                    throw new RuntimeException("Unsupport type");
            }
        }
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
