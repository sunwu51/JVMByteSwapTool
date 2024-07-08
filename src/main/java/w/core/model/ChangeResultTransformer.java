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
import w.core.compiler.WCompiler;
import w.core.constant.Codes;
import w.web.message.ChangeResultMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
            // use asm
            result = changeResultByASM(origin);
        }
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
        ClassReader cr = new ClassReader(origin);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, ClassReader.EXPAND_FRAMES);
        MethodNode[] nodes  = getOuterAndReplacementMethodNode(classNode);
        MethodNode outerNode = nodes[0], replacementNode = nodes[1];
        // replace the innerMethod with the replacement
        InsnList list = new InsnList();
        int curMaxLocals = outerNode.maxLocals;
        for (AbstractInsnNode instruction : outerNode.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode mnode = (MethodInsnNode) instruction;
                if (mnode.name.equals(innerMethod) && (
                        mnode.owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                ) {
                    int[] indexes = transArgsToVars(replacementNode.desc, curMaxLocals, list);
                    int resVarIndex = indexes[indexes.length - 1];
                    Map<LabelNode, LabelNode> labels = new HashMap<>();
                    // clone all labels
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LabelNode) labels.put((LabelNode) repInsn, new LabelNode());
                    }
                    LabelNode endLabel = new LabelNode();
                    // clone every node in replacement, ignore linenumber, return.
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LineNumberNode) continue;
                        if (repInsn.getOpcode() == RETURN) {
                            list.add(new JumpInsnNode(GOTO, endLabel));
                            continue;
                        }
                        if (repInsn instanceof LabelNode) {
                            LabelNode t = labels.get(repInsn);
                            list.add(t);
                            continue;
                        }
                        if (repInsn instanceof VarInsnNode) {
                            VarInsnNode newInsn = (VarInsnNode)repInsn.clone(labels);
                            newInsn.var += curMaxLocals;
                            list.add(newInsn);
                            continue;
                        }
                        if (repInsn instanceof IincInsnNode) {
                            IincInsnNode newInsn = (IincInsnNode)repInsn.clone(labels);
                            newInsn.var += curMaxLocals;
                            list.add(newInsn);
                            continue;
                        }
                        if (repInsn instanceof MethodInsnNode) {
                            MethodInsnNode cur = (MethodInsnNode) repInsn;
                            // $proceed(); will call the original method
                            if (cur.owner.equals("w/InlineWrapper") && cur.name.equals("$proceed")) {
                                Type[] argumentTypes = Type.getArgumentTypes(replacementNode.desc);
                                for (int i = 0; i < argumentTypes.length - 1; i++) {
                                    list.add(loadVar(argumentTypes[i], indexes[i]));
                                }
                                list.add(instruction.clone(null));
                                continue;
                            }
                        }
                        list.add(repInsn.clone(labels));
                    }
                    list.add(endLabel);
                    // push the result to stack
                    Type resType = Type.getReturnType(mnode.desc);
                    list.add(loadVar(resType, resVarIndex));
                    // update maxLocals for the next inline process
                    curMaxLocals +=  replacementNode.maxLocals;
                    // add try-catch
                    replacementNode.tryCatchBlocks.stream().map(it -> new TryCatchBlockNode(
                            labels.get(it.start), labels.get(it.end), labels.get(it.handler),
                            it.type
                    )).forEach(b -> {
                        if (outerNode.tryCatchBlocks == null) {
                            outerNode.tryCatchBlocks = new ArrayList<>();
                        }
                        outerNode.tryCatchBlocks.add(b);
                    });
                    continue;
                }
            }
            list.add(instruction);
        }
        outerNode.instructions.clear();
        outerNode.instructions.add(list);
        // Create a class writer to modify the class
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(classWriter);
        byte[] result = classWriter.toByteArray();
        return result;
    }

    private MethodNode[] getOuterAndReplacementMethodNode(ClassNode classNode) throws CompileException, IOException {
        String paramDes = paramTypesToDescriptor(paramTypes);
        Optional<MethodNode> outerNodeOpt = classNode.methods.stream()
                .filter(it->it.name.equals(method) && it.desc.startsWith(paramDes)).findFirst();

        if (!outerNodeOpt.isPresent()) {
            throw new IllegalArgumentException("Method " + method + paramDes + " not declared here");
        }
        MethodNode outerNode = outerNodeOpt.get();
        // check
        Set<String> distinctMethod = new HashSet<>();
        AtomicReference<MethodInsnNode> methodInsnRef = new AtomicReference<>();
        Arrays.stream(outerNode.instructions.toArray()).forEach(it -> {
            if (it instanceof MethodInsnNode) {
                MethodInsnNode item = (MethodInsnNode) it;
                if (item.name.equals(innerMethod) && (
                        item.owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                ) {
                    String methodCode = item.getOpcode() + "#" + item.owner + "#" + item.name + "#" + item.desc;
                    distinctMethod.add(methodCode);
                    methodInsnRef.compareAndSet(null, item);
                    if (distinctMethod.size() > 1 ) {
                        throw new IllegalArgumentException("Multi methods match the " + innerClassName + "#" + innerMethod + ", " +
                                "which have different owner/desc");
                    }
                }
            }
        });
        if (distinctMethod.isEmpty()) {
            throw new IllegalArgumentException("No methods match the *#" + innerMethod);
        }
        MethodInsnNode methodInsn = methodInsnRef.get();
        MethodNode replacementNode = getReplacementMethodNode(methodInsn.getOpcode() == INVOKESTATIC, methodInsn.owner, methodInsn.desc);
        return new MethodNode[]{outerNode, replacementNode};
    }

    private static byte[] compile(boolean staticMethod, String owner, String desc, String body) throws CompileException, IOException {
        String template = getTemplate();
        Type[] paramTypes = Type.getArgumentTypes(desc);
        Type returnType = Type.getReturnType(desc);
        List<String> paramClassNames = Arrays.stream(paramTypes).map(Type::getClassName).collect(Collectors.toList());

        StringBuilder fields_placeholder = new StringBuilder();
        StringBuilder args_placeholder = new StringBuilder();

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
                .replace("{{package_placeholder}}", "package w;")
                .replace("{{return_type}}", returnType.getClassName())
                ;

        byte[] res = WCompiler.compileWholeClass(sourceCode);
        return res;
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
        ClassReader rcr = new ClassReader(compile(isStatic, owner, descriptor, message.getBody()));
        // A container to collect the injection method insn
        ClassNode classNode = new ClassNode();
        rcr.accept(classNode, ClassReader.EXPAND_FRAMES);
        return classNode.methods.stream().filter(it -> it.name.equals("replace")).findFirst().get();
    }

    private int[] transArgsToVars(String desc, int offset, InsnList enList) {
        Type[] argumentTypes = Type.getArgumentTypes(desc);

        // indexes is the index of the argument mapping.
        // well for the argumentTypes, a[0]=`this` a[1]=arg1 ... a[last]=returnValue
        // the variableOffset shouldn't use the index from 0, because of inline, need add an offset
        int[] indexes = new int[argumentTypes.length];
        indexes[0] = offset;
        int curLen = argumentTypes[0].getSize();
        for (int i = 1; i < argumentTypes.length; i++) {
            indexes[i] = indexes[i - 1] + curLen;
            curLen = argumentTypes[i].getSize();
        }
        // reverse pop
        // the last argument is the return value of original method. Assign a default value 0/null
        for (AbstractInsnNode _t : storeVarWithDefaultValue(
                argumentTypes[indexes.length - 1], indexes[indexes.length - 1])) {
            enList.add(_t);
        }
        // the other arguments now in the top of stack, pop each one to a localVariable
        for (int i = argumentTypes.length - 2; i >= 0; i--) {
            Type t = argumentTypes[i];
            switch (t.getSort()) {
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    enList.add(new VarInsnNode(ISTORE, indexes[i]));
                    break;
                case Type.FLOAT:
                    enList.add(new VarInsnNode(FSTORE, indexes[i]));
                    break;
                case Type.DOUBLE:
                    enList.add(new VarInsnNode(DSTORE, indexes[i]));
                    break;
                case Type.LONG:
                    enList.add(new VarInsnNode(LSTORE, indexes[i]));
                    break;
                case Type.ARRAY:
                case Type.OBJECT:
                    enList.add(new VarInsnNode(ASTORE, indexes[i]));
                    break;
                default:
                    throw new RuntimeException("Unsupport type");
            }
        }
        return indexes;
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
