package w.web.util;

import javassist.*;
import lombok.extern.slf4j.Slf4j;
import w.Global;

import java.util.List;
import java.util.Objects;

public class ClassUtils {
    public static CtMethod checkMethodExists(String className, String method, List<String> paramTypes) throws NotFoundException {
        for (Class c : Global.instrumentation.getAllLoadedClasses()) {
            if (c.getName().equals(className)) {
                System.out.println(c.getName() + " loaded by " + c.getClassLoader());
                Global.classPool.appendClassPath(new LoaderClassPath(c.getClassLoader()));
                Thread.currentThread().setContextClassLoader(c.getClassLoader());
            }
        }

        CtClass c = Global.classPool.get(className);
        out: for (CtMethod declaredMethod : c.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method)) {
                if (paramTypes == null) return declaredMethod;
                CtClass[] pc = declaredMethod.getParameterTypes();
                if (pc.length != paramTypes.size()) {
                    continue;
                }
                for (int i = 0; i < pc.length; i++) {
                    String pcName = pc[i].getName();
                    if (!pcName.equals(paramTypes.get(i))) {
                        continue out;
                    }
                }
                return declaredMethod;
            }
        }
        throw new NotFoundException("Method with paramTypes " + paramTypes + " not exist");
    }
}
