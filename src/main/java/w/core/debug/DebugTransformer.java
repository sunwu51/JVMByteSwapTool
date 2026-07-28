package w.core.debug;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import w.core.asm.WAdviceAdapter;
import w.core.model.BaseClassTransformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class DebugTransformer extends BaseClassTransformer {
    private static final String BRIDGE = "w/bridge/DiagnosticBridge";
    private static final String ENTER_DESCRIPTOR =
            "(Ljava/lang/String;)Ljava/lang/Object;";
    private static final String HIT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;"
                    + "[Ljava/lang/Object;)V";
    private static final String HIT_WITH_LOCALS_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;"
                    + "[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;)V";
    private static final String CALL_HIT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;"
                    + "[Ljava/lang/String;[Ljava/lang/Object;"
                    + "[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Throwable;ZZ)V";
    private static final String EXIT_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Throwable;Z)V";
    private static final String BREAKPOINT_EXIT = "debugBreakpointExit";

    private final String methodName;
    private final String descriptor;
    private final int originalLine;
    private volatile List<String> parameterNames = Collections.emptyList();
    private volatile List<String> parameterTypes = Collections.emptyList();
    private volatile List<LocalVariable> breakpointVariables = Collections.emptyList();
    private volatile List<List<LocalVariable>> breakpointVariableScopes = Collections.emptyList();
    private volatile List<Boolean> breakpointReceiverScopes = Collections.emptyList();
    private volatile List<Boolean> breakpointCallScopes = Collections.emptyList();
    private volatile List<Boolean> breakpointExitScopes = Collections.emptyList();
    private volatile String breakpointVariableMessage;

    DebugTransformer(String id, String className, ClassLoader loader,
                     String methodName, String descriptor, int originalLine) {
        uuid = UUID.fromString(id);
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.originalLine = originalLine;
        targetClassLoader(loader);
    }

    @Override
    public byte[] transform(byte[] origin) {
        ClassReader reader = new ClassReader(origin);
        prepareBreakpointVariables(reader);
        ClassWriter writer = new ClassWriter(
                reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return getTargetClassLoader();
            }
        };
        AtomicBoolean methodFound = new AtomicBoolean();
        AtomicBoolean lineFound = new AtomicBoolean();
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String methodDescriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(
                        access, name, methodDescriptor, signature, exceptions);
                if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                methodFound.set(true);
                return new DebugAdvice(
                        delegate, access, name, methodDescriptor, lineFound);
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!methodFound.get()) {
            throw new IllegalArgumentException("Target method not found: "
                    + methodName + descriptor);
        }
        if (!lineFound.get()) {
            throw new IllegalArgumentException(
                    "Executable bytecode line not found: " + originalLine);
        }
        status = 1;
        return writer.toByteArray();
    }

    List<String> parameterNames() {
        return parameterNames;
    }

    List<String> parameterTypes() {
        return parameterTypes;
    }

    List<LocalVariable> breakpointVariables() {
        return breakpointVariables;
    }

    String breakpointVariableMessage() {
        return breakpointVariableMessage;
    }

    @Override
    public String desc() {
        return "Debug_" + className + "#" + methodName + ":" + originalLine;
    }

    private final class DebugAdvice extends WAdviceAdapter {
        private final int methodAccess;
        private final String methodDescriptor;
        private final Type returnType;
        private final AtomicBoolean lineFound;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();
        private final List<String> methodParameterNames = new ArrayList<>();
        private final List<Integer> methodParameterAccess = new ArrayList<>();
        private final Map<Integer, String> localNames = new HashMap<>();
        private int tokenIndex;
        private int currentLine = -1;
        private int breakpointLineOccurrence;
        private List<LocalVariable> currentBreakpointVariables = Collections.emptyList();
        private boolean currentReceiverAvailable;
        private boolean currentBreakpointHasCall;
        private boolean currentBreakpointIsExit;
        private boolean breakpointCallCaptured;

        DebugAdvice(MethodVisitor visitor, int access, String name,
                    String methodDescriptor, AtomicBoolean lineFound) {
            super(Opcodes.ASM9, visitor, access, name, methodDescriptor);
            this.methodAccess = access;
            this.methodDescriptor = methodDescriptor;
            this.returnType = Type.getReturnType(methodDescriptor);
            this.lineFound = lineFound;
        }

        @Override
        public void visitParameter(String name, int access) {
            methodParameterNames.add(name);
            methodParameterAccess.add(access);
            super.visitParameter(name, access);
        }

        @Override
        protected void onMethodEnter() {
            tokenIndex = newLocal(Type.getType(Object.class));
            visitLdcInsn(uuid.toString());
            visitMethodInsn(INVOKESTATIC, BRIDGE, "debugEnter",
                    ENTER_DESCRIPTOR, false);
            mv.visitVarInsn(ASTORE, tokenIndex);
            visitLabel(tryStart);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            currentLine = line;
            if (line != originalLine) {
                currentBreakpointHasCall = false;
                breakpointCallCaptured = false;
                return;
            }
            lineFound.set(true);
            List<LocalVariable> variables = breakpointVariables;
            if (breakpointLineOccurrence < breakpointVariableScopes.size()) {
                variables = breakpointVariableScopes.get(breakpointLineOccurrence);
            }
            breakpointLineOccurrence++;
            currentBreakpointVariables = variables;
            currentReceiverAvailable = breakpointLineOccurrence - 1
                    < breakpointReceiverScopes.size()
                    && breakpointReceiverScopes.get(breakpointLineOccurrence - 1);
            currentBreakpointHasCall = breakpointLineOccurrence - 1
                    < breakpointCallScopes.size()
                    && breakpointCallScopes.get(breakpointLineOccurrence - 1);
            currentBreakpointIsExit = breakpointLineOccurrence - 1
                    < breakpointExitScopes.size()
                    && breakpointExitScopes.get(breakpointLineOccurrence - 1);
            breakpointCallCaptured = false;
            if (currentBreakpointHasCall) {
                return;
            }
            emitLineHit(variables);
        }

        private void emitLineHit(List<LocalVariable> variables) {
            visitLdcInsn(uuid.toString());
            mv.visitVarInsn(ALOAD, tokenIndex);
            if ((methodAccess & ACC_STATIC) == 0 && currentReceiverAvailable) {
                visitVarInsn(ALOAD, 0);
            } else {
                visitInsn(ACONST_NULL);
            }
            loadArgArray();
            emitBreakpointVariables(variables);
            visitMethodInsn(INVOKESTATIC, BRIDGE, "debugHit",
                    HIT_WITH_LOCALS_DESCRIPTOR, false);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String callDescriptor, boolean isInterface) {
            if (!currentBreakpointHasCall || breakpointCallCaptured
                    || currentLine != originalLine || BRIDGE.equals(owner)) {
                super.visitMethodInsn(opcode, owner, name, callDescriptor, isInterface);
                return;
            }

            breakpointCallCaptured = true;

            int callArgsIndex = asmSubCallStoreParamsArray(callDescriptor);
            Label callStart = new Label();
            Label callEnd = new Label();
            Label callCatch = new Label();
            Label done = new Label();
            visitTryCatchBlock(callStart, callEnd, callCatch, "java/lang/Throwable");
            visitLabel(callStart);
            super.visitMethodInsn(opcode, owner, name, callDescriptor, isInterface);
            visitLabel(callEnd);
            int resultIndex = asmStoreRetObject(mv, callDescriptor);
            emitCallHit(callDescriptor, callArgsIndex, resultIndex, -1);
            goTo(done);

            visitLabel(callCatch);
            int throwableIndex = newLocal(Type.getType(Throwable.class));
            storeLocal(throwableIndex);
            emitCallHit(callDescriptor, callArgsIndex, -1, throwableIndex);
            loadLocal(throwableIndex);
            throwException();
            visitLabel(done);
        }

        private void emitCallHit(String callDescriptor, int callArgsIndex,
                                 int resultIndex, int throwableIndex) {
            visitLdcInsn(uuid.toString());
            loadLocal(tokenIndex);
            if ((methodAccess & ACC_STATIC) == 0 && currentReceiverAvailable) {
                loadThis();
            } else {
                visitInsn(ACONST_NULL);
            }
            loadArgArray();
            Type[] callTypes = Type.getArgumentTypes(callDescriptor);
            push(callTypes.length);
            newArray(Type.getType(String.class));
            for (int index = 0; index < callTypes.length; index++) {
                dup();
                push(index);
                visitLdcInsn(callTypes[index].getClassName());
                arrayStore(Type.getType(String.class));
            }
            loadLocal(callArgsIndex);
            emitBreakpointVariables(currentBreakpointVariables);
            if (resultIndex >= 0) {
                loadLocal(resultIndex);
            } else {
                visitInsn(ACONST_NULL);
            }
            if (throwableIndex >= 0) {
                loadLocal(throwableIndex);
            } else {
                visitInsn(ACONST_NULL);
            }
            push(Type.getReturnType(callDescriptor).getSort() == Type.VOID);
            push(currentBreakpointIsExit);
            visitMethodInsn(INVOKESTATIC, BRIDGE, "debugCallHit",
                    CALL_HIT_DESCRIPTOR, false);
        }

        private void emitBreakpointVariables(List<LocalVariable> variables) {
            push(variables.size());
            visitTypeInsn(ANEWARRAY, "java/lang/String");
            for (int index = 0; index < variables.size(); index++) {
                dup();
                push(index);
                visitLdcInsn(variables.get(index).name);
                visitInsn(AASTORE);
            }

            push(variables.size());
            visitTypeInsn(ANEWARRAY, "java/lang/String");
            for (int index = 0; index < variables.size(); index++) {
                dup();
                push(index);
                visitLdcInsn(variables.get(index).type);
                visitInsn(AASTORE);
            }

            push(variables.size());
            visitTypeInsn(ANEWARRAY, "java/lang/Object");
            for (int index = 0; index < variables.size(); index++) {
                LocalVariable variable = variables.get(index);
                dup();
                push(index);
                loadLocalValue(variable);
                visitInsn(AASTORE);
            }
        }

        private void loadLocalValue(LocalVariable variable) {
            Type type = Type.getType(variable.descriptor);
            visitVarInsn(type.getOpcode(ILOAD), variable.slot);
            if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
                box(type);
            }
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                if (currentLine == originalLine) {
                    int throwableIndex = newLocal(Type.getType(Throwable.class));
                    dup();
                    mv.visitVarInsn(ASTORE, throwableIndex);
                    emitBreakpointExit(-1, throwableIndex, false);
                }
                return;
            }
            int resultIndex = storeReturnReference();
            if (currentLine == originalLine) {
                emitBreakpointExit(resultIndex, -1, returnType.getSort() == Type.VOID);
            }
            mv.visitVarInsn(ALOAD, tokenIndex);
            mv.visitVarInsn(ALOAD, resultIndex);
            visitInsn(ACONST_NULL);
            push(returnType.getSort() == Type.VOID);
            visitMethodInsn(INVOKESTATIC, BRIDGE, "debugExit",
                    EXIT_DESCRIPTOR, false);
        }

        private void emitBreakpointExit(int resultIndex, int throwableIndex,
                                        boolean voidResult) {
            mv.visitVarInsn(ALOAD, tokenIndex);
            if (resultIndex >= 0) {
                mv.visitVarInsn(ALOAD, resultIndex);
            } else {
                visitInsn(ACONST_NULL);
            }
            if (throwableIndex >= 0) {
                mv.visitVarInsn(ALOAD, throwableIndex);
            } else {
                visitInsn(ACONST_NULL);
            }
            push(voidResult);
            visitMethodInsn(INVOKESTATIC, BRIDGE, BREAKPOINT_EXIT,
                    EXIT_DESCRIPTOR, false);
        }

        @Override
        public void visitLocalVariable(String name, String localDescriptor,
                                       String signature, Label start, Label end, int index) {
            if (name != null && !"this".equals(name)) {
                localNames.putIfAbsent(index, name);
            }
            super.visitLocalVariable(name, localDescriptor, signature, start, end, index);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            visitLabel(tryEnd);
            visitLabel(catchHandler);
            int throwableIndex = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, throwableIndex);
            mv.visitVarInsn(ALOAD, tokenIndex);
            visitInsn(ACONST_NULL);
            mv.visitVarInsn(ALOAD, throwableIndex);
            push(false);
            visitMethodInsn(INVOKESTATIC, BRIDGE, "debugExit",
                    EXIT_DESCRIPTOR, false);
            mv.visitVarInsn(ALOAD, throwableIndex);
            visitInsn(ATHROW);
            visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
            captureParameterMetadata();
            super.visitMaxs(maxStack, maxLocals);
        }

        private int storeReturnReference() {
            int resultIndex = newLocal(Type.getType(Object.class));
            switch (returnType.getSort()) {
                case Type.DOUBLE:
                case Type.LONG:
                    dup2();
                    box(returnType);
                    break;
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.INT:
                case Type.FLOAT:
                case Type.SHORT:
                case Type.BYTE:
                    dup();
                    box(returnType);
                    break;
                case Type.ARRAY:
                case Type.OBJECT:
                    dup();
                    break;
                case Type.VOID:
                default:
                    visitInsn(ACONST_NULL);
                    break;
            }
            mv.visitVarInsn(ASTORE, resultIndex);
            return resultIndex;
        }

        private void captureParameterMetadata() {
            Type[] arguments = Type.getArgumentTypes(methodDescriptor);
            List<String> names = new ArrayList<>(arguments.length);
            List<String> types = new ArrayList<>(arguments.length);
            int localIndex = (methodAccess & ACC_STATIC) == 0 ? 1 : 0;
            for (int index = 0; index < arguments.length; index++) {
                String name = methodParameterName(index);
                if (name == null || name.isEmpty()) {
                    name = localNames.get(localIndex);
                }
                names.add(name == null || name.isEmpty()
                        ? "parameter" + (index + 1) : name);
                types.add(arguments[index].getClassName());
                localIndex += arguments[index].getSize();
            }
            parameterNames = Collections.unmodifiableList(names);
            parameterTypes = Collections.unmodifiableList(types);
        }

        private String methodParameterName(int index) {
            if (index >= methodParameterNames.size()) {
                return null;
            }
            if (index < methodParameterAccess.size()
                    && (methodParameterAccess.get(index) & ACC_SYNTHETIC) != 0) {
                return null;
            }
            return methodParameterNames.get(index);
        }
    }

    private void prepareBreakpointVariables(ClassReader reader) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode target = null;
        for (MethodNode method : node.methods) {
            if (methodName.equals(method.name) && descriptor.equals(method.desc)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            return;
        }

        List<Integer> lineIndexes = new ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : target.instructions) {
            if (instruction instanceof LineNumberNode
                    && ((LineNumberNode) instruction).line == originalLine) {
                lineIndexes.add(target.instructions.indexOf(
                        ((LineNumberNode) instruction).start));
            }
        }

        boolean hasLocalVariableTable = target.localVariables != null
                && !target.localVariables.isEmpty();
        List<List<LocalVariable>> scopes = new ArrayList<>();
        List<Boolean> receiverScopes = new ArrayList<>();
        List<Boolean> callScopes = new ArrayList<>();
        List<Boolean> exitScopes = new ArrayList<>();
        for (int lineIndex : lineIndexes) {
            scopes.add(buildBreakpointVariables(target, lineIndex, hasLocalVariableTable));
            receiverScopes.add((target.access & Opcodes.ACC_STATIC) == 0
                    && (!"<init>".equals(methodName)
                    || constructorThisInitialized(target, lineIndex)));
            callScopes.add(hasMethodCall(target, lineIndex));
            exitScopes.add(hasMethodExit(target, lineIndex));
        }
        breakpointVariableScopes = Collections.unmodifiableList(scopes);
        breakpointReceiverScopes = Collections.unmodifiableList(receiverScopes);
        breakpointCallScopes = Collections.unmodifiableList(callScopes);
        breakpointExitScopes = Collections.unmodifiableList(exitScopes);
        breakpointVariables = scopes.isEmpty()
                ? Collections.emptyList() : scopes.get(0);
        if (!hasLocalVariableTable) {
            breakpointVariableMessage = "Local variable debug information unavailable; breakpoint return values cannot be captured.";
        } else if (scopes.stream().allMatch(List::isEmpty)) {
            breakpointVariableMessage = "No local return variables are available at this breakpoint.";
        }
    }

    private List<LocalVariable> buildBreakpointVariables(
            MethodNode target, int lineIndex, boolean hasLocalVariableTable) {
        Map<Integer, LocalVariable> visible = new LinkedHashMap<>();
        if (lineIndex >= 0 && hasLocalVariableTable) {
            for (LocalVariableNode local : target.localVariables) {
                if (local.name == null || local.desc == null || local.index < 0) {
                    continue;
                }
                int start = target.instructions.indexOf(local.start);
                int end = target.instructions.indexOf(local.end);
                if (start < 0 || end <= start || lineIndex < start || lineIndex >= end) {
                    continue;
                }
                Type type;
                try {
                    type = Type.getType(local.desc);
                } catch (Throwable ignored) {
                    continue;
                }
                if (local.index + type.getSize() > target.maxLocals) {
                    continue;
                }
                visible.putIfAbsent(local.index,
                        new LocalVariable(local.name, type.getClassName(), local.desc, local.index));
            }
        }

        Type[] arguments = Type.getArgumentTypes(descriptor);
        Map<Integer, LocalVariable> parameters = new LinkedHashMap<>();
        int slot = (target.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < arguments.length; index++) {
            LocalVariable parameter = methodParameter(target, index, slot, arguments[index]);
            parameters.put(slot, parameter);
            slot += arguments[index].getSize();
        }

        Map<Integer, LocalVariable> selected = new LinkedHashMap<>();
        for (LocalVariable variable : visible.values()) {
            if ("this".equals(variable.name)
                    || sameVariable(variable, parameters.get(variable.slot))) {
                continue;
            }
            addVariable(selected, variable);
        }
        return Collections.unmodifiableList(new ArrayList<>(selected.values()));
    }

    private LocalVariable methodParameter(
            MethodNode method, int index, int slot, Type argumentType) {
        LocalVariableNode earliest = null;
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.index != slot || !argumentType.getDescriptor().equals(local.desc)) {
                    continue;
                }
                if (earliest == null
                        || method.instructions.indexOf(local.start)
                        < method.instructions.indexOf(earliest.start)) {
                    earliest = local;
                }
            }
        }
        LocalVariable local = earliest == null ? null : new LocalVariable(
                earliest.name, argumentType.getClassName(), argumentType.getDescriptor(), slot);
        String name = parameterName(index, method, local);
        return new LocalVariable(name, argumentType.getClassName(),
                argumentType.getDescriptor(), slot);
    }

    private boolean constructorThisInitialized(MethodNode method, int lineIndex) {
        if (lineIndex < 0) {
            return false;
        }
        for (int index = 0; index < lineIndex; index++) {
            org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof MethodInsnNode
                    && instruction.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(((MethodInsnNode) instruction).name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethodCall(MethodNode method, int lineIndex) {
        if (lineIndex < 0) {
            return false;
        }
        boolean lineNumberSeen = false;
        for (int index = lineIndex; index < method.instructions.size(); index++) {
            org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LineNumberNode) {
                if (lineNumberSeen) {
                    return false;
                }
                lineNumberSeen = true;
                continue;
            }
            if (lineNumberSeen && instruction instanceof MethodInsnNode) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethodExit(MethodNode method, int lineIndex) {
        if (lineIndex < 0) {
            return false;
        }
        boolean lineNumberSeen = false;
        for (int index = lineIndex; index < method.instructions.size(); index++) {
            org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LineNumberNode) {
                if (lineNumberSeen) {
                    return false;
                }
                lineNumberSeen = true;
                continue;
            }
            int opcode = instruction.getOpcode();
            if (lineNumberSeen && (opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                    || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                    || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN
                    || opcode == Opcodes.ATHROW)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameVariable(LocalVariable left, LocalVariable right) {
        return right != null
                && left.slot == right.slot
                && left.name.equals(right.name)
                && left.descriptor.equals(right.descriptor);
    }

    private String parameterName(int index, MethodNode method, LocalVariable local) {
        if (method.parameters != null && index < method.parameters.size()) {
            String name = method.parameters.get(index).name;
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return local == null || local.name == null || local.name.isEmpty()
                ? "parameter" + (index + 1) : local.name;
    }

    private static void addVariable(Map<Integer, LocalVariable> selected,
                                    LocalVariable variable) {
        selected.putIfAbsent(variable.slot, variable);
    }

    static final class LocalVariable {
        final String name;
        final String type;
        final String descriptor;
        final int slot;

        LocalVariable(String name, String type, String descriptor, int slot) {
            this.name = name;
            this.type = type;
            this.descriptor = descriptor;
            this.slot = slot;
        }
    }
}
