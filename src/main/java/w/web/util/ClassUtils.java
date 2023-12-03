package w.web.util;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

@Slf4j
public class ClassUtils {
    public static CtMethod checkMethodExists(String className, String method, List<String> paramTypes) throws NotFoundException {
        CtClass c = ClassPool.getDefault().get(className);
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
