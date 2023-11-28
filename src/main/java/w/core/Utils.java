package w.core;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

@Slf4j
public class Utils {
    public static Method checkMethodExists(String className, String method, List<String> paramTypes) {
        Class c = null;
        try {
            c = Class.forName(className);
            log.info("类存在: {}", className);
        } catch (ClassNotFoundException e) {
            log.error("类不存在: {}", className);
            throw new NoClassException();
        }
        out: for (Method declaredMethod : c.getDeclaredMethods()) {
            log.info( "{} = {} ? {}", declaredMethod.getName(), method, Objects.equals(declaredMethod.getName(), method));
            if (Objects.equals(declaredMethod.getName(), method)) {
                if (paramTypes == null) return declaredMethod;
                Class[] pc = declaredMethod.getParameterTypes();
                if (pc.length != paramTypes.size()) {
                    log.error("同名方法，但是参数个数不一致。");
                    continue;
                }
                for (int i = 0; i < pc.length; i++) {
                    String pcName = pc[i].getName();
                    if (pc[i].getName().startsWith("[L") && pc[i].getName().endsWith(";")) {
                        pcName = pcName.substring(2, pcName.length() - 1) + "[]";
                    }
                    if (!pcName.equals(paramTypes.get(i))) {
                        log.error("同名方法，但是参数类型不一致。{}!={}", pcName, paramTypes.get(i));
                        continue out;
                    }
                }
                log.info("方法存在: {}", method);
                return declaredMethod;
            }
        }
        throw new NoMethodException();
    }
}
