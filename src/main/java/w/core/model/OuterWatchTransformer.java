package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fi.iki.elonen.NanoHTTPD;
import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import w.Global;
import w.web.message.OuterWatchMessage;

import java.io.ByteArrayInputStream;
import java.util.Objects;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class OuterWatchTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient OuterWatchMessage message;

    String method;

    String innerClassName;

    String innerMethod;

    int printFormat;


    public OuterWatchTransformer(OuterWatchMessage watchMessage) {
        this.message = watchMessage;
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.innerClassName = watchMessage.getInnerSignature().split("#")[0];
        this.innerMethod = watchMessage.getInnerSignature().split("#")[1];
        this.printFormat = watchMessage.getPrintFormat();
        this.traceId = watchMessage.getId();
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
                if (m.getMethodName().equals(innerMethod)) {
                    if (innerClassName.equals("*") || m.getClassName().equals(innerClassName)) {
                        String code = "{" +
                                "long start = System.currentTimeMillis();" +
                                "String req = null;" +
                                "String res = null;" +
                                "Throwable t = null;" +
                                "try {" +
                                "    $_ = $proceed($$);" +
                                "} catch (Throwable e) {" +
                                "   t = e;" +
                                "   throw e;" +
                                "} finally {" +
                                "long duration = System.currentTimeMillis() - start;" +
                                "int printFormat = " + printFormat +";" +
                                "if (printFormat == 1) {" +
                                "   req = Arrays.toString($args);" +
                                "   res = \"\" + $_;" +
                                "} else if (printFormat == 2) {" +
                                "req = w.Global.toJson($args);" +
                                "res = w.Global.toJson(($w)$_);" +
                                "} else {" +
                                "   req = w.Global.toString($args);" +
                                "   res = w.Global.toString(($w)$_);" +
                                "}"  +
                                "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");" +
                                "w.Global.info(\"line" + m.getLineNumber() + ",cost:\"+duration+\"ms,req:\"+req+\",res:\"+res+\",exception:\"+t);" +
                                "w.util.RequestUtils.clearRequestCtx();" +
                                "}}";
                     if (!Global.nonVerifying) {
                         code = "{" +
                                 "long start = System.currentTimeMillis();" +
                                 "$_ = $proceed($$);" +
                                 "long duration = System.currentTimeMillis() - start;" +
                                 "String req = null;" +
                                 "String res = null;" +
                                 "int printFormat = " + printFormat +";" +
                                 "if (printFormat == 1) {" +
                                 "   req = Arrays.toString($args);" +
                                 "   res = \"\" + $_;" +
                                 "} else if (printFormat == 2) {" +
                                 "req = w.Global.toJson($args);" +
                                 "res = w.Global.toJson(($w)$_);" +
                                 "} else {" +
                                 "   req = w.Global.toString($args);" +
                                 "   res = w.Global.toString(($w)$_);" +
                                 "}"  +
                                 "w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");" +
                                 "w.Global.info(\"line" + m.getLineNumber() + ",cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);" +
                                 "w.util.RequestUtils.clearRequestCtx();" +
                                 "}";
                     }
                     m.replace(code);
                    }
                }
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
        return "OuterWatch_" + getClassName() + "#" + method;
    }
}
