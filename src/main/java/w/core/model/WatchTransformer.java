package w.core.model;

import javassist.*;
import lombok.Data;
import ognl.*;
import w.Global;
import w.web.message.WatchMessage;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class WatchTransformer extends BaseClassTransformer {

    transient WatchMessage message;

    String method;

    int printFormat;

    public WatchTransformer(WatchMessage watchMessage) {
        this.setClassName(watchMessage.getSignature().split("#")[0]);
        this.method = watchMessage.getSignature().split("#")[1];
        this.message = watchMessage;
        this.traceId = watchMessage.getId();
        this.printFormat = watchMessage.getPrintFormat();
    }

    @Override
    public byte[] transform(String className, byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method)) {
                addWatchCodeToMethod(declaredMethod);
            }
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
        ctMethod.insertAfter("endTime = System.currentTimeMillis();");
        ctMethod.insertAfter("duration = endTime - startTime;");
        if (printFormat == 1) {
            ctMethod.insertAfter("req = Arrays.toString($args);");
            ctMethod.insertAfter("res = \"\" + $_;");
        } else if (printFormat == 2) {
            ctMethod.insertAfter("try {req = w.Global.toJson($args);} catch (Exception e) {req = \"convert json error\";}");
            ctMethod.insertAfter("try {res = w.Global.toJson($_);} catch (Exception e) {res = \"convert json error\";}");
        } else {
            ctMethod.insertAfter("req = w.Global.toString($args);");
            ctMethod.insertAfter("res = w.Global.toString($_);");
        }
        ctMethod.insertAfter("w.util.RequestUtils.fillCurThread(\"" + message.getId() + "\");");
        ctMethod.insertAfter("w.Global.info(\"cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);");
        ctMethod.insertAfter("w.util.RequestUtils.clearRequestCtx();");
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
