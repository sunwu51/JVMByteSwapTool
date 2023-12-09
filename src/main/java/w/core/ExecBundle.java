package w.core;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import lombok.Data;
import w.Global;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * @author Frank
 * @date 2023/12/9 20:50
 */
@Data
public class ExecBundle {
    Object inst;
    CtClass ctClass;
    CtMethod ctMethod;

    public void invoke() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Global.log(1, "start to invoke");
        inst.getClass().getDeclaredMethod("exec")
                .invoke(inst);
    }

    public synchronized void changeBodyAndInvoke(String body) throws Exception {
        Global.log(1, "start to change body");
        ctClass.defrost();
        body = "{" +
            (Global.springApplicationContext != null ?
                "org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext ctx = (org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext) w.Global.springApplicationContext;\n"
                : "") + body +"}";
        Swapper.getInstance().changeBody(
                new MethodId(ctClass.getName(), ctMethod.getName(), new ArrayList<>()),
                body
        );
        invoke();
    }
}
