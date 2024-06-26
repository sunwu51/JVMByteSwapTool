package w.core.model;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.*;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import w.Global;
import w.web.message.TraceMessage;

@Data
public class TraceTransformer extends BaseClassTransformer {
    public static ThreadLocal<Map<String, int[]>> traceContent = ThreadLocal.withInitial(LinkedHashMap::new);
    public static ThreadLocal<Integer> stackDeep = ThreadLocal.withInitial(()->1);

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
    public byte[] transform(String className, byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        boolean effect = false;
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method)) {
                if ((declaredMethod.getModifiers() & Modifier.ABSTRACT) != 0) {
                    throw new IllegalArgumentException("Cannot change abstract method.");
                }
                if ((declaredMethod.getModifiers() & Modifier.NATIVE) != 0) {
                    throw new IllegalArgumentException("Cannot change native method.");
                }
                addTraceCodeToMethod(declaredMethod);
                effect = true;
            }
        }
        if (!effect) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        byte[] result = ctClass.toBytecode();
        ctClass.detach();
        status = 1;
        return result;
    }

    private void addTraceCodeToMethod(CtMethod ctMethod) throws CannotCompileException, NotFoundException {
        ctMethod.instrument(new ExprEditor() {
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getClassName().startsWith("java.lang") && !m.getClassName().equals("java.lang.Thread")) {
                    return;
                }
                String code = "{" +
                        "   if (\"" + m.getMethodName() + "\".equals(\"" + method + "\")) {" +
                        "       int deep = ((Integer)w.core.model.TraceTransformer.stackDeep.get()).intValue();" +
                        "       w.core.model.TraceTransformer.stackDeep.set(new Integer(++deep));" +
                        "   }" +
                        "   long start = System.currentTimeMillis();\n" +
                        "   $_ = $proceed($$);\n" +
                        "   long duration = System.currentTimeMillis() - start;\n" +
                        "   String sig = \"line" + m.getLineNumber() + "," + m.getClassName() + "#" + m.getMethodName() + "\";\n" +
                        "   LinkedHashMap map = ((LinkedHashMap)w.core.model.TraceTransformer.traceContent.get()); \n" +
                        "   if (!map.containsKey(sig)) {map.put(sig, new int[]{0,0});}" +
                        "   int[] arr = (int[])map.get(sig); \n" +
                        "   arr[0] += duration; arr[1] += 1;" +

                        "}";
                m.replace(code);
            }
        });
        ctMethod.addLocalVariable("s", CtClass.longType);
        ctMethod.addLocalVariable("cost", CtClass.longType);
        ctMethod.insertBefore("s = System.currentTimeMillis();");
        ctMethod.insertAfter("{" +
                "int deep = ((Integer)w.core.model.TraceTransformer.stackDeep.get()).intValue();\n" +
                "w.core.model.TraceTransformer.stackDeep.set(new Integer(--deep));" +
                "if (deep <= 0) {" +
                "   w.core.model.TraceTransformer.stackDeep.remove();\n" +
                "   cost = System.currentTimeMillis() - s;\n" +
                "   if (cost >= " + minCost +") {" +
                "     w.Global.checkCountAndUnload(\"" + uuid + "\");\n"+
                "     w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");\n" +
                "     String str = \"" + className + "#" + method + ", total cost:\"+cost+\"ms\\\n\";\n" +
                "     LinkedHashMap map = (LinkedHashMap)w.core.model.TraceTransformer.traceContent.get(); \n" +
                "     Iterator it = map.entrySet().iterator(); \n" +
                "     while (it.hasNext()) {\n" +
                "         java.util.Map.Entry e = (java.util.Map.Entry)it.next();\n" +
                "         String k = e.getKey().toString();\n" +
                "         int[] v = (int[])e.getValue();\n" +
                "         if (v[0] == 0 && " + ignoreZero + ") \n {} else {" +
                "          str += \">>\" + k + \" hit:\" + v[1] + \"times, total cost:\" + v[0] + \"ms\\\n\";}\n" +
                "     }" +
                "     w.Global.info(str);\n" +
                "     w.util.RequestUtils.clearRequestCtx();\n" +
                "   }\n" +
                "   w.core.model.TraceTransformer.traceContent.remove();\n" +
                "}" +
                "}");
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
}
