package w.core.model;

import com.alibaba.fastjson2.annotation.JSONField;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import org.codehaus.commons.compiler.CompileException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import w.Global;
import w.core.compiler.WCompiler;
import w.util.IOUtils;
import w.core.constant.Codes;
import w.web.message.ChangeResultMessage;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.IllegalClassFormatException;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.DSTORE;
import static org.objectweb.asm.Opcodes.FSTORE;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.LSTORE;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.RETURN;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class ChangeResultTransformer extends BaseClassTransformer {

    @JSONField(serialize = false, deserialize = false)
    transient ChangeResultMessage message;

    String method;

    List<String> paramTypes;

    String innerClassName;

    String innerMethod;

    int mode;

    boolean includeNested;

    private final Map<String, Set<String>> targetMethods = new LinkedHashMap<>();

    private final List<ChangeResultResult.EnhancedCall> enhancedCalls = new ArrayList<>();

    public ChangeResultTransformer(ChangeResultMessage message) {
        this.className = message.getClassName();
        this.method = message.getMethod();
        this.paramTypes = message.getParamTypes();
        this.innerMethod = message.getInnerMethod();
        this.innerClassName = message.getInnerClassName();
        this.message = message;
        this.traceId = message.getId();
        this.mode = message.getMode();
        this.includeNested = message.isIncludeNested();
        addTargetMethod(className, method);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        if (className == null) {
            return origin;
        }
        className = className.replace("/", ".");
        if (targetMethods.containsKey(className) && matchesTargetLoader(loader)) {
            try {
                byte[] r = transform(origin, className);
                recordApplySuccess(loader, className);
                Global.info(className + " transformer " + uuid + " added success <(^-^)>");
                return r;
            } catch (Throwable e) {
                recordApplyFailure(loader, className, e);
                Global.error(className + " transformer " + uuid + " added fail -(′д｀)-: ", e);
                CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
            }
        }
        return null;
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        return transform(origin, className);
    }

    private byte[] transform(byte[] origin, String currentClassName) throws Exception {
        byte[] result = null;
        if (mode == Codes.CHANGE_RESULT_MODE_USE_JAVASSIST) {
            // use javassist $_=xxx to change result
            result = changeResultByJavassist(origin, currentClassName);
        } else if (mode == Codes.CHANGE_RESULT_MODE_USE_ASM) {
            // use asm
            result = changeResultByASM(origin, currentClassName);
        }
        status = 1;
        return result;
    }

    public Set<Class<?>> prepareNestedTargets(Set<Class<?>> rootClasses) {
        if (!includeNested) {
            return Collections.emptySet();
        }
        Set<Class<?>> nestedClasses = new LinkedHashSet<>();
        for (Class<?> rootClass : rootClasses) {
            collectNestedTargets(rootClass);
        }
        Set<String> extraClassNames = new HashSet<>(targetMethods.keySet());
        extraClassNames.remove(className);
        for (Class<?> rootClass : rootClasses) {
            ClassLoader loader = rootClass.getClassLoader();
            for (String extraClassName : extraClassNames) {
                try {
                    nestedClasses.add(Class.forName(extraClassName, false, loader));
                } catch (Throwable e) {
                    Global.debug("change result nested class not loaded: " + extraClassName + ", " + e.getMessage());
                }
            }
        }
        return nestedClasses;
    }

    private void collectNestedTargets(Class<?> rootClass) {
        ClassLoader loader = rootClass.getClassLoader();
        Deque<ScanTarget> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.push(new ScanTarget(className, method));
        while (!pending.isEmpty()) {
            ScanTarget target = pending.pop();
            String scanKey = target.className + "#" + target.methodName;
            if (!visited.add(scanKey)) {
                continue;
            }
            byte[] bytes = readClassBytes(loader, target.className);
            if (bytes == null) {
                continue;
            }
            try {
                new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!"*".equals(target.methodName) && !Objects.equals(name, target.methodName)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                                if (bootstrapMethodArguments != null) {
                                    for (Object bootstrapMethodArgument : bootstrapMethodArguments) {
                                        if (!(bootstrapMethodArgument instanceof Handle)) {
                                            continue;
                                        }
                                        Handle handle = (Handle) bootstrapMethodArgument;
                                        if (!handle.getName().startsWith("lambda$")) {
                                            continue;
                                        }
                                        String lambdaOwner = handle.getOwner().replace("/", ".");
                                        addTargetMethod(lambdaOwner, handle.getName());
                                        pending.push(new ScanTarget(lambdaOwner, handle.getName()));
                                    }
                                }
                                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                if (opcode == NEW && type != null) {
                                    String nestedClassName = type.replace("/", ".");
                                    if (nestedClassName.startsWith(target.className + "$")) {
                                        addAllMethodsTarget(nestedClassName);
                                        pending.push(new ScanTarget(nestedClassName, "*"));
                                    }
                                }
                                super.visitTypeInsn(opcode, type);
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            } catch (Throwable e) {
                Global.debug("change result collect nested targets error: " + target.className + ", " + e.getMessage());
            }
        }
    }

    private byte[] readClassBytes(ClassLoader loader, String className) {
        String resourceName = className.replace('.', '/') + ".class";
        InputStream inputStream = null;
        try {
            inputStream = loader == null
                    ? ClassLoader.getSystemResourceAsStream(resourceName)
                    : loader.getResourceAsStream(resourceName);
            if (inputStream == null) {
                return null;
            }
            return IOUtils.readAllBytes(inputStream);
        } catch (Throwable e) {
            Global.debug("change result read class bytes error: " + className + ", " + e.getMessage());
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class ScanTarget {
        private final String className;
        private final String methodName;

        private ScanTarget(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }

    private void addTargetMethod(String targetClassName, String targetMethod) {
        targetMethods.computeIfAbsent(targetClassName, k -> new LinkedHashSet<>()).add(targetMethod);
    }

    private void addAllMethodsTarget(String targetClassName) {
        targetMethods.computeIfAbsent(targetClassName, k -> new LinkedHashSet<>()).add("*");
    }

    private boolean shouldInstrumentMethod(String currentClassName, int access, String name) {
        Set<String> methods = targetMethods.get(currentClassName);
        if (methods == null) {
            return false;
        }
        if (methods.contains(name)) {
            return true;
        }
        return methods.contains("*") && !name.equals("<init>") && !name.equals("<clinit>")
                && (access & ACC_SYNTHETIC) == 0;
    }

    private boolean hasNestedTargetClasses() {
        for (String targetClassName : targetMethods.keySet()) {
            if (!Objects.equals(targetClassName, className)) {
                return true;
            }
        }
        return false;
    }

    private void recordEnhancedCall(MethodNode outerNode, MethodInsnNode mnode, int line) {
        ChangeResultResult.EnhancedCall call = new ChangeResultResult.EnhancedCall();
        call.setLine(line);
        call.setOuterSignature(className + "#" + outerNode.name);
        call.setOuterDescriptor(outerNode.desc);
        call.setInnerSignature(mnode.owner.replace("/", ".") + "#" + mnode.name);
        call.setInnerDescriptor(mnode.desc);
        synchronized (enhancedCalls) {
            enhancedCalls.add(call);
        }
    }

    private void recordEnhancedCall(CtMethod outerMethod, MethodCall m) {
        ChangeResultResult.EnhancedCall call = new ChangeResultResult.EnhancedCall();
        call.setLine(m.getLineNumber());
        call.setOuterSignature(className + "#" + outerMethod.getName());
        call.setOuterDescriptor(outerMethod.getSignature());
        call.setInnerSignature(m.getClassName() + "#" + m.getMethodName());
        call.setInnerDescriptor(m.getSignature());
        synchronized (enhancedCalls) {
            enhancedCalls.add(call);
        }
    }

    public synchronized ChangeResultResult getResult() {
        ChangeResultResult result = new ChangeResultResult();
        synchronized (enhancedCalls) {
            result.setEnhancedCalls(new ArrayList<>(enhancedCalls));
        }
        return result;
    }

    @Override
    public List<String> getExtraClassNames() {
        List<String> extraClassNames = new ArrayList<>(targetMethods.keySet());
        extraClassNames.remove(className);
        return extraClassNames;
    }

    private byte[] changeResultByJavassist(byte[] origin, String currentClassName) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        boolean effect = false;
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (!shouldInstrumentMethod(currentClassName, declaredMethod.getModifiers(), declaredMethod.getName())) {
                continue;
            }
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
                            recordEnhancedCall(declaredMethod, m);
                            m.replace("{" + message.getBody() + "}");
                        }
                    }
                }
            });
            effect = true;
        }
        if (!effect) {
            if (Objects.equals(currentClassName, className)) {
                throw new IllegalArgumentException("Method not declared here.");
            }
            ctClass.detach();
            return origin;
        }
        byte[] result = ctClass.toBytecode();
        ctClass.detach();

        return result;
    }

    private byte[] changeResultByASM(byte[] origin, String currentClassName) throws CompileException, IOException {
        ClassReader cr = new ClassReader(origin);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, ClassReader.EXPAND_FRAMES);
        List<MethodNode> targetNodes = targetMethodNodes(classNode, currentClassName);
        if (targetNodes.isEmpty()) {
            if (Objects.equals(currentClassName, className)) {
                throw new IllegalArgumentException("Method " + method + paramTypesToDescriptor(paramTypes) + " not declared here");
            }
            return origin;
        }
        boolean replacedAny = false;
        for (MethodNode outerNode : targetNodes) {
            replacedAny |= applyReplacement(classNode, outerNode, false);
        }
        if (!replacedAny) {
            if (Objects.equals(currentClassName, className) && !hasNestedTargetClasses()) {
                throw new IllegalArgumentException("No methods match the *#" + innerMethod);
            }
            return origin;
        }
        // Create a class writer to modify the class
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return getTargetClassLoader();
            }
        };

        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private List<MethodNode> targetMethodNodes(ClassNode classNode, String currentClassName) {
        String paramDes = paramTypesToDescriptor(paramTypes);
        return classNode.methods.stream()
                .filter(it -> shouldInstrumentMethod(currentClassName, it.access, it.name))
                .filter(it -> !it.name.equals(method) || it.desc.startsWith(paramDes))
                .collect(Collectors.toList());
    }

    private boolean applyReplacement(ClassNode classNode, MethodNode outerNode, boolean failIfNoMatch) throws CompileException, IOException {
        Set<String> distinctMethod = new HashSet<>();
        AtomicReference<MethodInsnNode> methodInsnRef = new AtomicReference<>();
        for (AbstractInsnNode it : outerNode.instructions.toArray()) {
            if (it instanceof MethodInsnNode) {
                MethodInsnNode item = (MethodInsnNode) it;
                if (item.name.equals(innerMethod) && (
                        item.owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                ) {
                    String methodCode = item.getOpcode() + "#" + item.owner + "#" + item.name + "#" + item.desc;
                    distinctMethod.add(methodCode);
                    methodInsnRef.compareAndSet(null, item);
                    if (distinctMethod.size() > 1) {
                        throw new IllegalArgumentException("Multi methods match the " + innerClassName + "#" + innerMethod + ", " +
                                "which have different owner/desc");
                    }
                }
            }
        }
        if (distinctMethod.isEmpty()) {
            if (failIfNoMatch) {
                throw new IllegalArgumentException("No methods match the *#" + innerMethod);
            }
            return false;
        }
        MethodInsnNode methodInsn = methodInsnRef.get();
        MethodNode replacementNode = getReplacementMethodNode(methodInsn.getOpcode() == INVOKESTATIC, methodInsn.owner, methodInsn.desc);
        // replace the innerMethod with the replacement
        InsnList list = new InsnList();
        int curMaxLocals = outerNode.maxLocals;
        int currentLine = -1;
        for (AbstractInsnNode instruction : outerNode.instructions) {
            if (instruction instanceof LineNumberNode) {
                currentLine = ((LineNumberNode) instruction).line;
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode mnode = (MethodInsnNode) instruction;
                if (mnode.name.equals(innerMethod) && (
                        mnode.owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                ) {
                    recordEnhancedCall(outerNode, mnode, currentLine);
                    int[] indexes = transArgsToVars(replacementNode.desc, curMaxLocals, list);
                    int resVarIndex = indexes[indexes.length - 1];
                    Map<LabelNode, LabelNode> labels = new HashMap<>();
                    // clone all labels
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LabelNode) {
                            labels.put((LabelNode) repInsn, new LabelNode());
                        }
                    }
                    LabelNode endLabel = new LabelNode();
                    // clone every node in replacement, ignore linenumber, return.
                    for (AbstractInsnNode repInsn : replacementNode.instructions) {
                        if (repInsn instanceof LineNumberNode) {
                            continue;
                        }
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
        return true;
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
                .replace("{{body_placeholder}}", normalizeAsmBody(body))
                .replace("{{package_placeholder}}", "package w;")
                .replace("{{return_type}}", returnType.getClassName())
                ;

        byte[] res = WCompiler.compileWholeClass(sourceCode);
        return res;
    }

    private static String normalizeAsmBody(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("\\$proceed\\s*\\(\\s*\\$\\$\\s*\\)", "\\$proceed()");
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
