package w.web.util;

import javassist.*;
import lombok.extern.slf4j.Slf4j;
import w.Global;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public class ClassUtils {
    static Logger log = Logger.getLogger(ClassUtils.class.getName());

    public static CtMethod checkMethodExists(String className, String method, List<String> paramTypes) throws NotFoundException {
        // append the class path if necessary
        for (Class c : Global.instrumentation.getAllLoadedClasses()) {
            if (c.getName().equals(className)) {
                log.info(c.getName() + " loaded by " + c.getClassLoader());
                Global.classPool.appendClassPath(new LoaderClassPath(c.getClassLoader()));
                Global.classToLoader.get().computeIfAbsent(className, k -> new HashSet<>()).add(c.getClassLoader());
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
