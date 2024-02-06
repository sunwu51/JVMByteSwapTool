package w.core;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import lombok.Data;
import w.Global;
import w.util.SpringUtils;
import w.web.message.ChangeBodyMessage;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * @author Frank
 * @date 2023/12/9 20:50
 */
@Data
public class ExecBundle {
    static Object inst;

    static {
        try {
            CtClass ctClass = Global.classPool.makeClass("w.Exec");
            CtMethod ctMethod = CtMethod.make("public void exec() {}", ctClass);
            ctClass.addMethod(ctMethod);
            // use the spring boot class loader
            Class c = ctClass.toClass(Global.getClassLoader());
            ctClass.detach();
            inst = c.newInstance();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
    public static void invoke() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Global.info("start to invoke");
        inst.getClass().getDeclaredMethod("exec")
                .invoke(inst);
    }

    public synchronized static void changeBodyAndInvoke(String body) throws Exception {
        ChangeBodyMessage message = new ChangeBodyMessage();
        message.setClassName("w.Exec");
        message.setMethod("exec");
        message.setParamTypes(new ArrayList<>());
        body = "{" + SpringUtils.generateSpringCtxCode() + body + "}";
        message.setBody(body);
        // remove the old transformer
        Global.activeTransformers
                .getOrDefault("w.Exec", new HashMap<>()).values().forEach(baseClassTransformers -> {
                    baseClassTransformers.forEach(transformer -> {
                        Global.instrumentation.removeTransformer(transformer);
                        Global.transformers.remove(transformer);
                    });
                });
        Global.activeTransformers
                .getOrDefault("w.Exec", new HashMap<>()).clear();
        Swapper.getInstance().swap(message);
        invoke();
    }
}
