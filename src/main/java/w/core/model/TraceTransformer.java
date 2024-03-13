package w.core.model;

import java.io.ByteArrayInputStream;
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
        this.className = traceMessage.getSignature().split("#")[0];
        this.method = traceMessage.getSignature().split("#")[1];
        this.traceId = traceMessage.getId();
    }

     @Override
    public byte[] transform(String className, byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method)) {
                addOuterWatchCodeToMethod(declaredMethod);
            }
        }
        byte[] result = ctClass.toBytecode();
        ctClass.detach();
        status = 1;
        return result;
    }

    private void addOuterWatchCodeToMethod(CtMethod ctMethod) throws CannotCompileException, NotFoundException {
        ctMethod.instrument(new ExprEditor() {
            public void edit(MethodCall m) throws CannotCompileException {
                String code = "{" +
                        "long start = System.currentTimeMillis();" +
                        "$_ = $proceed($$);" +
                        "long duration = System.currentTimeMillis() - start;" +
                        "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");" +
                        "w.Global.info(\"line" + m.getLineNumber() + "," + m.getSignature() + "cost:\"+duration+\"ms\");" +
                        "w.util.RequestUtils.clearRequestCtx();" ;
                m.replace(code);
            }
        });
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
