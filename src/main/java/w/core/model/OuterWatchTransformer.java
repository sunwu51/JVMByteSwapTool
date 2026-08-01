package w.core.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import w.Global;
import w.core.asm.WAdviceAdapter;
import w.core.diagnostic.DiagnosticRuntime;
import w.core.diagnostic.OuterWatchConfig;
import w.web.message.OuterWatchMessage;

import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.NEW;


/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class OuterWatchTransformer extends BaseClassTransformer {

    public static ThreadLocal<Map<String, Integer>> outerWatchCtx = ThreadLocal.withInitial(HashMap::new);

    @JSONField(serialize = false, deserialize = false)
    transient OuterWatchMessage message;

    String method;

    String innerClassName;

    String innerMethod;

    int printFormat;

    boolean includeNested;

    boolean printLvt;

    String ognl;

    int depthForJson;

    Map<String, String> variables;

    private final Map<String, Set<String>> targetMethods = new LinkedHashMap<>();

    private final Map<String, OuterWatchResult.InspectedCall> inspectedCalls = new LinkedHashMap<>();


    public OuterWatchTransformer(OuterWatchMessage watchMessage) {
        this.message = watchMessage;
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.innerClassName = watchMessage.getInnerSignature().split("#")[0];
        this.innerMethod = watchMessage.getInnerSignature().split("#")[1];
        this.printFormat = watchMessage.getPrintFormat();
        this.traceId = watchMessage.getId();
        this.includeNested = watchMessage.isIncludeNested();
        this.printLvt = watchMessage.isPrintLvt();
        this.ognl = watchMessage.getOgnl();
        this.depthForJson = watchMessage.getDepthForJson() <= 0 ? 3 : watchMessage.getDepthForJson();
        this.variables = watchMessage.getVariables();
        addTargetMethod(this.className, this.method);
    }

    public OuterWatchConfig config() {
        String shortSignature = innerClassName.substring(innerClassName.lastIndexOf('.') + 1)
                + "#" + innerMethod;
        return new OuterWatchConfig(uuid.toString(), traceId, shortSignature, printFormat,
                ognl, depthForJson, printLvt, variables);
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        return transform(origin, className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        if (className == null) {
            return origin;
        }
        className = className.replace("/", ".");
        if (targetMethods.containsKey(className) && matchesTargetLoader(loader)) {
            try {
                byte[] r = transform(origin, className);
                recordApplySuccess(loader, className);
                Global.info(className + " transformer " + uuid +  " added success <(^-^)>");
                return r;
            } catch (Throwable e) {
                recordApplyFailure(loader, className, e);
                Global.error(className + " transformer " + uuid + " added fail -(′д｀)-: ", e);
                CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
            }
        }
        return null;
    }

    public Set<Class<?>> prepareNestedTargets(Set<Class<?>> rootClasses) {
        // Include lambda and anonymous-class methods compiled outside the root method.
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
                    Global.debug("outer watch nested class not loaded: " + extraClassName + ", " + e.getMessage());
                }
            }
        }
        return nestedClasses;
    }

    private void collectNestedTargets(Class<?> rootClass) {
        String resourceName = rootClass.getName().replace('.', '/') + ".class";
        InputStream inputStream = null;
        try {
            ClassLoader loader = rootClass.getClassLoader();
            inputStream = loader == null
                    ? ClassLoader.getSystemResourceAsStream(resourceName)
                    : loader.getResourceAsStream(resourceName);
            if (inputStream == null) {
                return;
            }
            ClassReader classReader = new ClassReader(inputStream);
            classReader.accept(new ClassVisitor(ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!Objects.equals(name, method)) {
                        return mv;
                    }
                    return new MethodVisitor(ASM9, mv) {
                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                            collectLambdaTarget(bootstrapMethodArguments);
                            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == NEW && type != null) {
                                String nestedClassName = type.replace("/", ".");
                                if (nestedClassName.startsWith(className + "$")) {
                                    addAllMethodsTarget(nestedClassName);
                                }
                            }
                            super.visitTypeInsn(opcode, type);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Throwable e) {
            Global.debug("outer watch collect nested targets error: " + rootClass.getName() + ", " + e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void collectLambdaTarget(Object[] bootstrapMethodArguments) {
        if (bootstrapMethodArguments == null) {
            return;
        }
        for (Object bootstrapMethodArgument : bootstrapMethodArguments) {
            if (!(bootstrapMethodArgument instanceof org.objectweb.asm.Handle)) {
                continue;
            }
            org.objectweb.asm.Handle handle = (org.objectweb.asm.Handle) bootstrapMethodArgument;
            if (!handle.getName().startsWith("lambda$")) {
                continue;
            }
            addTargetMethod(handle.getOwner().replace("/", "."), handle.getName());
        }
    }

    private void addTargetMethod(String targetClassName, String targetMethod) {
        targetMethods.computeIfAbsent(targetClassName, k -> new LinkedHashSet<>()).add(targetMethod);
    }

    private void addAllMethodsTarget(String targetClassName) {
        targetMethods.computeIfAbsent(targetClassName, k -> new LinkedHashSet<>()).add("*");
    }

    public Map<String, Set<String>> getTargetMethods() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        targetMethods.forEach((key, value) -> copy.put(key, new LinkedHashSet<>(value)));
        return copy;
    }

    public synchronized OuterWatchResult getOuterWatchResult() {
        OuterWatchResult result = new OuterWatchResult();
        result.setInspectedCalls(new ArrayList<>(inspectedCalls.values()));
        return result;
    }

    @Override
    public List<String> getExtraClassNames() {
        // These classes are also targets and must be considered when removing the transformer.
        List<String> extraClassNames = new ArrayList<>(targetMethods.keySet());
        extraClassNames.remove(className);
        return extraClassNames;
    }

    private byte[] transform(byte[] origin, String currentClassName) throws Exception {
        ClassReader classReader = new ClassReader(origin);
        Map<String, List<LocalSnapshot>> localSnapshots = printLvt
                ? collectLocalSnapshots(origin, currentClassName)
                : Collections.emptyMap();
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return getTargetClassLoader();
            }
        };

        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!shouldWatchMethod(currentClassName, access, name)) {
                    return mv;
                }
                String containingMethod = name;
                String containingDescriptor = descriptor;
                boolean outerMethod = currentClassName.equals(className) && name.equals(method);
                List<LocalSnapshot> methodLocalSnapshots = localSnapshots.getOrDefault(
                        name + descriptor, Collections.emptyList());
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int line;
                    private int startTimeVarIndex;
                    private int paramsArrayVarIndex;
                    private int returnObjectVarIndex;
                    private int exceptionObjectIndex;

                    private int tokenIndex;
                    private int methodCallIndex;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        super.visitLineNumber(line, start);
                        this.line = line;
                    }

                    @Override
                    protected void onMethodEnter() {
                        if (outerMethod) {
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/bridge/DiagnosticBridge",
                                    "outerEnter", "(Ljava/lang/String;)V", false);
                        }
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        if (outerMethod) {
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/bridge/DiagnosticBridge",
                                    "outerExit", "(Ljava/lang/String;)V", false);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
                        LocalSnapshot localSnapshot = methodCallIndex < methodLocalSnapshots.size()
                                ? methodLocalSnapshots.get(methodCallIndex)
                                : LocalSnapshot.EMPTY;
                        methodCallIndex++;
                        boolean hit = (owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                                && name.equals(innerMethod);
                        if (hit) {
                            recordInspectedCall(currentClassName, containingMethod, containingDescriptor,
                                    line, owner, name, descriptor);
                            Label recordStart = new Label();
                            Label guardEnd = new Label();
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/bridge/DiagnosticBridge",
                                    "outerShouldRecord", "(Ljava/lang/String;)Z", false);
                            mv.visitJumpInsn(IFNE, recordStart);
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                            mv.visitJumpInsn(GOTO, guardEnd);
                            mv.visitLabel(recordStart);

                            // long start = System.currentTimeMillis();
                            startTimeVarIndex = asmStoreStartTime(mv);
                            paramsArrayVarIndex = asmSubCallStoreParamsArray(descriptor);
                            tokenIndex = newLocal(Type.getType(Object.class));
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitVarInsn(Opcodes.ALOAD, paramsArrayVarIndex);
                            loadLocalSnapshot(localSnapshot);
                            mv.visitMethodInsn(INVOKESTATIC, "w/bridge/DiagnosticBridge",
                                    "outerCallEnter", "(Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;"
                                            + "[Ljava/lang/Object;)Ljava/lang/Object;", false);
                            mv.visitVarInsn(Opcodes.ASTORE, tokenIndex);

                            Label tryStart = new Label();
                            Label tryEnd = new Label();
                            Label catchStart = new Label();
                            mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable");
                            mv.visitLabel(tryStart);
                            // execute original method
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);

                            returnObjectVarIndex = asmStoreRetObject(mv, descriptor);
                            postProcess(false);

                            mv.visitLabel(tryEnd);
                            Label invokeEnd = new Label();
                            mv.visitJumpInsn(Opcodes.GOTO, invokeEnd);

                            mv.visitLabel(catchStart);
                            exceptionObjectIndex = newLocal(Type.getType(Throwable.class));
                            mv.visitVarInsn(Opcodes.ASTORE, exceptionObjectIndex);
                            postProcess(true);
                            mv.visitVarInsn(Opcodes.ALOAD, exceptionObjectIndex);
                            mv.visitInsn(Opcodes.ATHROW);
                            Label catchEnd = new Label();
                            mv.visitLabel(catchEnd);
                            mv.visitLabel(invokeEnd);

                            mv.visitLabel(guardEnd);
                        } else {
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                        }
                    }

                    private void postProcess(boolean whenThrow) {
                        mv.visitVarInsn(ALOAD, tokenIndex);
                        push(line);
                        loadLocal(startTimeVarIndex, Type.LONG_TYPE);
                        loadThisOrNull();
                        loadLocal(paramsArrayVarIndex, Type.getType(Object[].class));
                        if (whenThrow) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            loadLocal(exceptionObjectIndex, Type.getType(Throwable.class));
                        } else {
                            loadLocal(returnObjectVarIndex, Type.getType(Object.class));
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                        mv.visitMethodInsn(INVOKESTATIC, "w/bridge/DiagnosticBridge", "outerCallExit",
                                "(Ljava/lang/Object;IJLjava/lang/Object;[Ljava/lang/Object;"
                                        + "Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
                    }

                    private void loadThisOrNull() {
                        if ((access & Opcodes.ACC_STATIC) == 0) {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                        } else {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                    }

                    private void loadLocalSnapshot(LocalSnapshot snapshot) {
                        if (!printLvt || snapshot.names.length == 0) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            return;
                        }
                        push(snapshot.names.length);
                        newArray(Type.getType(String.class));
                        for (int i = 0; i < snapshot.names.length; i++) {
                            dup();
                            push(i);
                            mv.visitLdcInsn(snapshot.names[i]);
                            arrayStore(Type.getType(String.class));
                        }
                        push(snapshot.names.length);
                        newArray(Type.getType(Object.class));
                        for (int i = 0; i < snapshot.names.length; i++) {
                            dup();
                            push(i);
                            visitVarInsn(snapshot.types[i].getOpcode(Opcodes.ILOAD), snapshot.slots[i]);
                            box(snapshot.types[i]);
                            arrayStore(Type.getType(Object.class));
                        }
                    }

                };
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] result = classWriter.toByteArray();
        status = 1;
        return result;
    }

    private synchronized void recordInspectedCall(String declaringClass, String outerMethod,
                                                  String outerDescriptor, int line, String owner,
                                                  String innerMethod, String innerDescriptor) {
        String innerOwner = owner.replace('/', '.');
        String key = declaringClass + '#' + outerMethod + outerDescriptor + ':' + line + ':'
                + innerOwner + '#' + innerMethod + innerDescriptor;
        inspectedCalls.computeIfAbsent(key, ignored -> {
            OuterWatchResult.InspectedCall call = new OuterWatchResult.InspectedCall();
            call.setLine(line);
            call.setOuterSignature(declaringClass + '#' + outerMethod);
            call.setOuterDescriptor(outerDescriptor);
            call.setInnerSignature(innerOwner + '#' + innerMethod);
            call.setInnerDescriptor(innerDescriptor);
            return call;
        });
    }

    private Map<String, List<LocalSnapshot>> collectLocalSnapshots(byte[] origin, String currentClassName) {
        ClassNode classNode = new ClassNode(ASM9);
        new ClassReader(origin).accept(classNode, ClassReader.SKIP_FRAMES);
        Map<String, List<LocalSnapshot>> result = new HashMap<>();
        for (MethodNode methodNode : classNode.methods) {
            if (!shouldWatchMethod(currentClassName, methodNode.access, methodNode.name)) {
                continue;
            }
            List<LocalSnapshot> snapshots = new ArrayList<>();
            for (AbstractInsnNode instruction : methodNode.instructions) {
                if (!(instruction instanceof MethodInsnNode)) {
                    continue;
                }
                int instructionIndex = methodNode.instructions.indexOf(instruction);
                List<String> names = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                List<Type> types = new ArrayList<>();
                if (methodNode.localVariables != null) {
                    for (LocalVariableNode local : methodNode.localVariables) {
                        int start = methodNode.instructions.indexOf(local.start);
                        int end = methodNode.instructions.indexOf(local.end);
                        if ("this".equals(local.name) || local.desc == null
                                || start < 0 || end < 0
                                || instructionIndex < start || instructionIndex >= end) {
                            continue;
                        }
                        names.add(local.name);
                        slots.add(local.index);
                        types.add(Type.getType(local.desc));
                    }
                }
                snapshots.add(new LocalSnapshot(names, slots, types));
            }
            result.put(methodNode.name + methodNode.desc, snapshots);
        }
        return result;
    }

    private static final class LocalSnapshot {
        private static final LocalSnapshot EMPTY = new LocalSnapshot(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        private final String[] names;
        private final int[] slots;
        private final Type[] types;

        private LocalSnapshot(List<String> names, List<Integer> slots, List<Type> types) {
            this.names = names.toArray(new String[0]);
            this.slots = new int[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                this.slots[i] = slots.get(i);
            }
            this.types = types.toArray(new Type[0]);
        }
    }

    private boolean shouldWatchMethod(String currentClassName, int access, String name) {
        Set<String> methods = targetMethods.get(currentClassName);
        if (methods == null) {
            return false;
        }
        if (methods.contains(name)) {
            return true;
        }
        return methods.contains("*") && !name.equals("<init>") && !name.equals("<clinit>") && (access & ACC_SYNTHETIC) == 0;
    }

    public static void enterOuterWatch(String uuid) {
        Map<String, Integer> map = outerWatchCtx.get();
        map.put(uuid, map.getOrDefault(uuid, 0) + 1);
    }

    public static void exitOuterWatch(String uuid) {
        Map<String, Integer> map = outerWatchCtx.get();
        Integer count = map.get(uuid);
        if (count == null || count <= 1) {
            map.remove(uuid);
        } else {
            map.put(uuid, count - 1);
        }
        if (map.isEmpty()) {
            outerWatchCtx.remove();
        }
    }

    public static boolean shouldRecord(String uuid) {
        return outerWatchCtx.get().containsKey(uuid);
    }

    public boolean equals(Object other) {
        if (other instanceof OuterWatchTransformer) {
            return this.uuid.equals(((OuterWatchTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "OuterWatch_" + getClassName() + "#" + method;
    }

    @Override
    public void clear() {
        Map<String, Integer> map = outerWatchCtx.get();
        map.remove(this.uuid.toString());
        if (map.isEmpty()) {
            outerWatchCtx.remove();
        }
        DiagnosticRuntime.unregister(uuid.toString());
    }
}
