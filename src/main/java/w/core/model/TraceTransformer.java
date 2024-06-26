package w.core.model;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import org.objectweb.asm.*;
import w.Global;
import w.core.asm.SbNode;
import w.core.asm.WAdviceAdapter;
import w.web.message.TraceMessage;

import static net.bytebuddy.jar.asm.Opcodes.ASM9;

@Data
public class TraceTransformer extends BaseClassTransformer {
//    public static ThreadLocal<Map<String, int[]>> traceContent = ThreadLocal.withInitial(LinkedHashMap::new);
//    public static ThreadLocal<Integer> stackDeep = ThreadLocal.withInitial(()->1);

    public static ThreadLocal<Map<String, TraceCtx>> traceCtx = ThreadLocal.withInitial(HashMap::new);

    transient TraceMessage message;

    String method;

    int minCost;

    boolean ignoreZero;

    public TraceTransformer(TraceMessage traceMessage) {
        this.message = traceMessage;
        this.className = traceMessage.getSignature().split("#")[0];
        this.method = traceMessage.getSignature().split("#")[1];
        this.traceId = traceMessage.getId();
        this.minCost = traceMessage.getMinCost();
        this.ignoreZero = traceMessage.isIgnoreZero();
    }

    @Override
    public byte[] transform(Class<?> claz, byte[] origin) throws Exception {
        ClassReader classReader = new ClassReader(origin);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        AtomicBoolean effect = new AtomicBoolean();
        classReader.accept(new ClassVisitor(ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method)) return mv;
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
                    }
                    @Override
                    protected void onMethodExit(int opcode) {
                        mv.visitVarInsn(LLOAD, startTimeVarIndex);
                        mv.visitLdcInsn((long) minCost);
                        mv.visitLdcInsn(uuid.toString());
                        mv.visitLdcInsn(traceId);
                        mv.visitLdcInsn(className + "#" + method);
                        mv.visitLdcInsn(ignoreZero);
                        mv.visitMethodInsn(INVOKESTATIC, "w/core/model/TraceTransformer", "traceSummary", "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
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
                        /*---------------------counter: if reach the limitation will remove the transformer----------------*/
                        mv.visitLdcInsn(uuid.toString());
                        mv.visitMethodInsn(INVOKESTATIC, "w/Global", "checkCountAndUnload", "(Ljava/lang/String;)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!effect.get()) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        byte[] result = classWriter.toByteArray();
        new FileOutputStream("T.class").write(result);
        status = 1;
        return result;
    }

    public static void subTrace(String uuid, String key, long duration) {
        Map<String, int[]> map = traceCtx.get().computeIfAbsent(uuid, k->new TraceCtx()).traceContent;
        int[] arr = map.computeIfAbsent(key, k->new int[2]);
        arr[0] += (int) duration;
        arr[1] += 1;
    }


    public static void traceSummary(long start, long minCost, String uuid, String traceId, String outerSig, boolean ignoreZero) {
        TraceCtx ctx = traceCtx.get().getOrDefault(uuid, new TraceCtx());
        int deep = --ctx.stackDeep;
        StringBuilder sb = new StringBuilder();
        if (deep <= 0) {
            long cost = System.currentTimeMillis() - start;
            if (cost >= minCost) {
                w.Global.checkCountAndUnload(uuid);
                w.util.RequestUtils.fillCurThread(traceId);
                sb.append(outerSig).append(", total cost:").append(cost).append("ms\n");
                Map<String, int[]> map = ctx.traceContent;
                map.forEach((k, v) -> {
                    if (v[0] != 0 || !ignoreZero) {
                        sb.append(">>").append(k).append(" hit:").append(v[1]).append("times, total cost:").append(v[0]).append("ms\n");
                    }
                });
                w.Global.info(sb);
            }
            traceCtx.remove();
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
        Map<String, int[]> traceContent = new HashMap<>();
    }
}
