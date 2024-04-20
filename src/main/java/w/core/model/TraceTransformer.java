package w.core.model;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.*;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import w.Global;
import w.web.message.TraceMessage;

@Data
public class TraceTransformer extends BaseClassTransformer {
    public static ThreadLocal<Map<String, int[]>> traceContent = ThreadLocal.withInitial(LinkedHashMap::new);

    transient TraceMessage message;

    String method;

    int minCost;

    public TraceTransformer(TraceMessage traceMessage) {
        this.message = traceMessage;
        this.className = traceMessage.getSignature().split("#")[0];
        this.method = traceMessage.getSignature().split("#")[1];
        this.traceId = traceMessage.getId();
        this.minCost = traceMessage.getMinCost();
    }

    @Override
    public byte[] transform(String className, byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        boolean effect = false;
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method)) {
                addTraceCodeToMethod(declaredMethod);
                effect = true;
            }
        }
        if (!effect) {
            throw new IllegalArgumentException("Class or Method not exist.");
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
        String str ="";

        ctMethod.addLocalVariable("s", CtClass.longType);
        ctMethod.addLocalVariable("cost", CtClass.longType);
        ctMethod.insertBefore("s = System.currentTimeMillis();");
        ctMethod.insertBefore("w.Global.checkCountAndUnload(\"" + uuid + "\");");
        ctMethod.insertAfter("{" +
                "cost = System.currentTimeMillis() - s;\n" +
                "if (cost >= " + minCost +") {" +
                "  w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");\n" +
                "  String str = \"" + className + "#" + method + ", total cost:\"+cost+\"ms\\\n\";\n" +
                "  LinkedHashMap map = (LinkedHashMap)w.core.model.TraceTransformer.traceContent.get(); \n" +
                "  Iterator it = map.entrySet().iterator(); \n" +
                "  while (it.hasNext()) {\n" +
                "      java.util.Map.Entry e = (java.util.Map.Entry)it.next();\n" +
                "      String k = e.getKey().toString();\n" +
                "      int[] v = (int[])e.getValue();\n" +
                "      str += \">>\" + k + \" hit:\" + v[1] + \"times, total cost:\" + v[0] + \"ms\\\n\";\n" +
                "  }" +
                "  w.core.model.TraceTransformer.traceContent.remove();\n" +
                "  w.Global.info(str);\n" +
                "  w.util.RequestUtils.clearRequestCtx();\n" +
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
