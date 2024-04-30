package w.core.model;

import javassist.*;
import lombok.Data;
import ognl.*;
import w.Global;
import w.web.message.WatchMessage;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class WatchTransformer extends BaseClassTransformer {

    transient WatchMessage message;

    String method;

    int printFormat;

    int minCost;

    public WatchTransformer(WatchMessage watchMessage) {
        this.className = watchMessage.getSignature().split("#")[0];
        this.method = watchMessage.getSignature().split("#")[1];
        this.message = watchMessage;
        this.traceId = watchMessage.getId();
        this.printFormat = watchMessage.getPrintFormat();
        this.minCost = watchMessage.getMinCost();
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
                addWatchCodeToMethod(declaredMethod);
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

    private void addWatchCodeToMethod(CtMethod ctMethod) throws CannotCompileException, NotFoundException {
        ctMethod.addLocalVariable("startTime", CtClass.longType);
        ctMethod.addLocalVariable("endTime", CtClass.longType);
        ctMethod.addLocalVariable("duration", CtClass.longType);
        ctMethod.addLocalVariable("req", Global.classPool.get("java.lang.String"));
        ctMethod.addLocalVariable("res", Global.classPool.get("java.lang.String"));
        ctMethod.insertBefore("startTime = System.currentTimeMillis();");
        ctMethod.insertBefore("w.Global.checkCountAndUnload(\"" + uuid + "\");\n");

        StringBuilder afterCode = new StringBuilder("{\n")
                .append("endTime = System.currentTimeMillis();\n")
                .append("duration = endTime - startTime;\n");

        afterCode.append("if (duration>=").append(minCost).append(") {\n");
        StringBuilder catchCode = new StringBuilder("{\n");
        if (printFormat == 1) {
            afterCode.append("req = Arrays.toString($args);");
            afterCode.append("res = \"\" + $_;");
            catchCode.append("String req = Arrays.toString($args);");
        } else if (printFormat == 2) {
            afterCode.append("req = w.Global.toJson($args);");
            afterCode.append("res = w.Global.toJson(($w)$_);");
            catchCode.append("String req = w.Global.toJson($args);");
        } else {
            afterCode.append("req = w.Global.toString($args);");
            afterCode.append("res = w.Global.toString(($w)$_);");
            catchCode.append("String req = w.Global.toString($args);");
        }
        afterCode.append("w.util.RequestUtils.fillCurThread(\"").append(message.getId()).append("\");")
                .append("w.Global.info(\"cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);")
                .append("w.util.RequestUtils.clearRequestCtx();")
                .append("}");
        afterCode.append("}");
        catchCode.append("w.util.RequestUtils.fillCurThread(\"").append(message.getId()).append("\");")
                .append("w.Global.info(\"req:\"+req+\",exception:\"+$e); throw $e;")
                .append("w.util.RequestUtils.clearRequestCtx();")
                .append("}");
        ctMethod.insertAfter(afterCode.toString());
        if (Global.nonVerifying) {
            ctMethod.addCatch(catchCode.toString(), Global.classPool.get("java.lang.Throwable"));
        }
    }

    public boolean equals(Object other) {
        if (other instanceof WatchTransformer) {
            return this.uuid.equals(((WatchTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "Watch_" + getClassName() + "#" + method;
    }
}
