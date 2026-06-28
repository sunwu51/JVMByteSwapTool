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
import w.Global;
import w.core.asm.Tool;
import w.core.asm.WAdviceAdapter;
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
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
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

    String ognl;

    int depthForJson;

    Map<String, String> variables;

    private final Map<String, Set<String>> targetMethods = new LinkedHashMap<>();


    public OuterWatchTransformer(OuterWatchMessage watchMessage) {
        this.message = watchMessage;
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.innerClassName = watchMessage.getInnerSignature().split("#")[0];
        this.innerMethod = watchMessage.getInnerSignature().split("#")[1];
        this.printFormat = watchMessage.getPrintFormat();
        this.traceId = watchMessage.getId();
        this.includeNested = watchMessage.isIncludeNested();
        this.ognl = watchMessage.getOgnl();
        this.depthForJson = watchMessage.getDepthForJson() <= 0 ? 3 : watchMessage.getDepthForJson();
        this.variables = watchMessage.getVariables();
        Tool.registerOgnlVariables(uuid.toString(), variables);
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

        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!shouldWatchMethod(currentClassName, access, name)) {
                    return mv;
                }
                boolean outerMethod = currentClassName.equals(className) && name.equals(method);
                return new WAdviceAdapter(ASM9, mv, access, name, descriptor) {
                    private int line;
                    private int startTimeVarIndex;

                    private int paramsVarIndex;

                    private int paramsArrayVarIndex;

                    private int returnValueVarIndex;

                    private int returnObjectVarIndex;

                    private int exceptionStringIndex;

                    private int exceptionObjectIndex;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        super.visitLineNumber(line, start);
                        this.line = line;
                    }

                    @Override
                    protected void onMethodEnter() {
                        if (outerMethod) {
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/OuterWatchTransformer", "enterOuterWatch", "(Ljava/lang/String;)V", false);
                        }
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        if (outerMethod) {
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/OuterWatchTransformer", "exitOuterWatch", "(Ljava/lang/String;)V", false);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
                        boolean hit = (owner.replace("/", ".").equals(innerClassName) || "*".equals(innerClassName))
                                && name.equals(innerMethod);
                        if (hit) {
                            Label recordStart = new Label();
                            Label guardEnd = new Label();
                            mv.visitLdcInsn(uuid.toString());
                            mv.visitMethodInsn(INVOKESTATIC, "w/core/model/OuterWatchTransformer", "shouldRecord", "(Ljava/lang/String;)Z", false);
                            mv.visitJumpInsn(IFNE, recordStart);
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                            mv.visitJumpInsn(GOTO, guardEnd);
                            mv.visitLabel(recordStart);

                            // long start = System.currentTimeMillis();
                            startTimeVarIndex = asmStoreStartTime(mv);
                            // String params = Arrays.toString(paramArray);
                            paramsArrayVarIndex = asmSubCallStoreParamsArray(descriptor);
                            paramsVarIndex = asmStoreObjectString(paramsArrayVarIndex, printFormat, depthForJson, true);

                            mv.visitLdcInsn(traceId);
                            mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "fillCurThread", "(Ljava/lang/String;)V", false);


                            Label tryStart = new Label();
                            Label tryEnd = new Label();
                            Label catchStart = new Label();
                            mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable");
                            mv.visitLabel(tryStart);
                            // execute original method
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);

                            returnObjectVarIndex = asmStoreRetObject(mv, descriptor);
                            returnValueVarIndex = asmStoreObjectString(returnObjectVarIndex, printFormat, depthForJson);
                            postProcess(false);

                            mv.visitLabel(tryEnd);
                            Label invokeEnd = new Label();
                            mv.visitJumpInsn(Opcodes.GOTO, invokeEnd);

                            mv.visitLabel(catchStart);
                            exceptionObjectIndex = newLocal(Type.getType(Throwable.class));
                            mv.visitVarInsn(Opcodes.ASTORE, exceptionObjectIndex);
                            mv.visitVarInsn(Opcodes.ALOAD, exceptionObjectIndex);
                            exceptionStringIndex = newLocal(Type.getType(String.class));
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                            mv.visitVarInsn(Opcodes.ASTORE, exceptionStringIndex);

                            postProcess(true);
                            mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "clearRequestCtx", "()V", false);
                            mv.visitVarInsn(Opcodes.ALOAD, exceptionObjectIndex);
                            mv.visitInsn(Opcodes.ATHROW);
                            Label catchEnd = new Label();
                            mv.visitLabel(catchEnd);
                            mv.visitLabel(invokeEnd);

                            mv.visitMethodInsn(INVOKESTATIC, "w/util/RequestUtils", "clearRequestCtx", "()V", false);
                            mv.visitLabel(guardEnd);
                        } else {
                            mv.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
                        }
                    }

                    private void postProcess(boolean whenThrow) {
                        push(line);
                        loadLocal(startTimeVarIndex, Type.LONG_TYPE);
                        push(uuid.toString());
                        push(traceId);
                        push(innerClassName.substring(innerClassName.lastIndexOf('.') + 1) + "#" + innerMethod);
                        loadLocal(paramsVarIndex, Type.getType(String.class));

                        if (whenThrow) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            mv.visitVarInsn(ALOAD, exceptionStringIndex);
                        } else {
                            mv.visitVarInsn(ALOAD, returnValueVarIndex);
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }

                        loadThisOrNull();
                        push(ognl == null ? "" : ognl);
                        push(printFormat);
                        loadLocal(paramsArrayVarIndex, Type.getType(Object[].class));
                        if (whenThrow) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            loadLocal(exceptionObjectIndex, Type.getType(Throwable.class));
                        } else {
                            loadLocal(returnObjectVarIndex, Type.getType(Object.class));
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                        push(depthForJson);
                        mv.visitMethodInsn(INVOKESTATIC, "w/core/asm/Tool", "outerWatchPostProcess", "(IJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;I[Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Throwable;I)V", false);
                    }

                    private void loadThisOrNull() {
                        if ((access & Opcodes.ACC_STATIC) == 0) {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                        } else {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        }
                    }

                };
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] result = classWriter.toByteArray();
        status = 1;
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
        Tool.unregisterOgnlVariables(uuid.toString());
    }
}
