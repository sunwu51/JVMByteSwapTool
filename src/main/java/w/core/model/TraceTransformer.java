package w.core.model;

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
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Data;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import w.Global;
import w.core.asm.WAdviceAdapter;
import w.web.message.TraceMessage;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.LLOAD;
import static org.objectweb.asm.Opcodes.NEW;

@Data
public class TraceTransformer extends BaseClassTransformer {

    public static ThreadLocal<Map<String, TraceCtx>> traceCtx = ThreadLocal.withInitial(HashMap::new);

    transient TraceMessage message;

    String method;

    int minCost;

    boolean ignoreZero;

    boolean includeNested;

    private final Map<String, Set<String>> targetMethods = new LinkedHashMap<>();

    public TraceTransformer(TraceMessage traceMessage) {
        this.message = traceMessage;
        this.className = traceMessage.getSignature().split("#")[0];
        this.method = traceMessage.getSignature().split("#")[1];
        this.traceId = traceMessage.getId();
        this.minCost = traceMessage.getMinCost();
        this.ignoreZero = traceMessage.isIgnoreZero();
        this.includeNested = traceMessage.isIncludeNested();
        addTargetMethod(this.className, this.method);
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
        if (targetMethods.containsKey(className)) {
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
                    Global.debug("trace nested class not loaded: " + extraClassName + ", " + e.getMessage());
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
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
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
            Global.debug("trace collect nested targets error: " + rootClass.getName() + ", " + e.getMessage());
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
            if (!(bootstrapMethodArgument instanceof Handle)) {
                continue;
            }
            Handle handle = (Handle) bootstrapMethodArgument;
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

    @Override
    public List<String> getExtraClassNames() {
        List<String> extraClassNames = new ArrayList<>(targetMethods.keySet());
        extraClassNames.remove(className);
        return extraClassNames;
    }

    private byte[] transform(byte[] origin, String currentClassName) throws Exception {
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return Global.getClassLoader();
            }
        };
        AtomicBoolean effect = new AtomicBoolean();
        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!shouldTraceMethod(currentClassName, access, name)) {
                    return mv;
                }
                boolean outerMethod = currentClassName.equals(className) && name.equals(method);
                effect.set(true);
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int startTimeVarIndex;

                    private int line;
                    @Override
                    public void visitLineNumber(int line, Label start) {
                        super.visitLineNumber(line, start);
                        this.line = line;
                    }

                    @Override
                    public void onMethodEnter(){
                        startTimeVarIndex = asmStoreStartTime(mv);
                        if (outerMethod) {
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/TraceTransformer", "enterTrace", "(Ljava/lang/String;)V", false);
                        }
                    }
                    @Override
                    protected void onMethodExit(int opcode) {
                        if (outerMethod) {
                            mv.visitVarInsn(LLOAD, startTimeVarIndex);
                            mv.visitLdcInsn((long) minCost);
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitLdcInsn(traceId);
                            mv.visitLdcInsn(className + "#" + method);
                            mv.visitLdcInsn(ignoreZero);
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/TraceTransformer", "traceSummary", "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
                        } else {
                            int localDurationIndex = asmCalculateCost(mv, startTimeVarIndex);
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitLdcInsn("line" + line + "," + currentClassName + "#" + name);
                            mv.visitVarInsn(LLOAD, localDurationIndex);
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/TraceTransformer", "subTrace", "(Ljava/lang/String;Ljava/lang/String;J)V", false);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
                        // by default ignore the <init>, append, toString methods
                        if (Global.ignoreTraceMethods.contains(name) ||
                            (owner.startsWith("java/lang") && !owner.startsWith("java/lang/Thread"))) {
                            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                            return;
                        }

                        if (owner.replace("/",".").equals(className) && method.equals(name)) {
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/TraceTransformer", "recursiveRecord", "(Ljava/lang/String;)V", false);
                        }

                        // long start = System.currentTimeMillis();
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                        int localStart = newLocal(Type.LONG_TYPE);
                        mv.visitVarInsn(LSTORE, localStart);

                        // execute original method
                        super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                        // long duration = System.currentTimeMillis() - start;
                        int localDurationIndex = asmCalculateCost(mv, localStart);
                        mv.visitLdcInsn(uuid.toString());
                        mv.visitLdcInsn("line" + line + "," + owner.replace("/", ".") + "#" + name);
                        mv.visitVarInsn(LLOAD, localDurationIndex);
                        mv.visitMethodInsn(INVOKESTATIC, "w/core/model/TraceTransformer", "subTrace", "(Ljava/lang/String;Ljava/lang/String;J)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!effect.get()) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        byte[] result = classWriter.toByteArray();
        status = 1;
        return result;
    }

    private boolean shouldTraceMethod(String currentClassName, int access, String name) {
        Set<String> methods = targetMethods.get(currentClassName);
        if (methods == null) {
            return false;
        }
        if (methods.contains(name)) {
            return true;
        }
        return methods.contains("*") && !name.equals("<init>") && !name.equals("<clinit>") && (access & ACC_SYNTHETIC) == 0;
    }

    public static void enterTrace(String uuid) {
        traceCtx.get().computeIfAbsent(uuid, k -> new TraceCtx());
    }

    public static void subTrace(String uuid, String key, long duration) {
        TraceCtx ctx = traceCtx.get().get(uuid);
        if (ctx == null) {
            return;
        }
        Map<String, int[]> map = ctx.traceContent;
        int[] arr = map.computeIfAbsent(key, k->new int[2]);
        arr[0] += (int) duration;
        arr[1] += 1;
    }


    public static void traceSummary(long start, long minCost, String uuid, String traceId, String outerSig, boolean ignoreZero) {
        TraceCtx ctx = traceCtx.get().get(uuid);
        if (ctx == null) {
            return;
        }
        int deep = --ctx.stackDeep;
        StringBuilder sb = new StringBuilder();
        if (deep <= 0) {
            long cost = System.currentTimeMillis() - start;
            if (cost >= minCost) {
                w.Global.checkCountAndUnload(uuid);
                w.util.RequestUtils.fillCurThread(traceId);
                try {
                    sb.append(outerSig).append(", total cost:").append(cost).append("ms\n");
                    Map<String, int[]> map = ctx.traceContent;
                    map.forEach((k, v) -> {
                        if (v[0] != 0 || !ignoreZero) {
                            sb.append(">>").append(k).append(" hit:").append(v[1]).append("times, total cost:").append(v[0]).append("ms\n");
                        }
                    });
                    w.Global.info(sb);
                } finally {
                    w.util.RequestUtils.clearRequestCtx();
                }
            }
            Map<String, TraceCtx> ctxMap = traceCtx.get();
            ctxMap.remove(uuid);
            if (ctxMap.isEmpty()) {
                traceCtx.remove();
            }
        }
    }

    public static void recursiveRecord(String uuid) {
        traceCtx.get().computeIfAbsent(uuid, k -> new TraceCtx()).stackDeep++;
    }

    public boolean equals(Object other) {
        if (other instanceof OuterWatchTransformer) {
            return this.uuid.equals(((OuterWatchTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "Trace_" + getClassName() + "#" + method;
    }

    @Override
    public void clear() {
        traceCtx.get().remove(this.uuid.toString());
    }

    public static class TraceCtx {
        int stackDeep = 1;
        Map<String, int[]> traceContent = new LinkedHashMap<>();
    }
}
