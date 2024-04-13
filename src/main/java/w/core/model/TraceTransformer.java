package w.core.model;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Objects;

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
    transient TraceMessage message;

    String method;

    public TraceTransformer(TraceMessage traceMessage) {
        this.message = traceMessage;
        this.className = traceMessage.getSignature().split("#")[0];
        this.method = traceMessage.getSignature().split("#")[1];
        this.traceId = traceMessage.getId();
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
                String code = "{" +
                        "long start = System.currentTimeMillis();\n" +
                        "$_ = $proceed($$);\n" +
                        "long duration = System.currentTimeMillis() - start;\n" +
                        "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");\n" +
                        "w.Global.info(\"line" + m.getLineNumber() + "," + m.getClassName() + "#" + m.getMethodName() + ",cost:\"+duration+\"ms\");\n" +
                        "w.util.RequestUtils.clearRequestCtx();" +
                        "}";
                m.replace(code);
            }
        });
        ctMethod.addLocalVariable("s", CtClass.longType);
        ctMethod.addLocalVariable("cost", CtClass.longType);
        ctMethod.insertBefore("s = System.currentTimeMillis();");
        ctMethod.insertBefore("w.Global.checkCountAndUnload(\"" + uuid + "\");");
        ctMethod.insertAfter("{" +
                "cost = System.currentTimeMillis() - s;" +
                "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");\n" +
                "w.Global.info(\"" + className + "#" + method + ", total cost:\"+cost+\"ms\");\n" +
                "w.util.RequestUtils.clearRequestCtx();" +
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
