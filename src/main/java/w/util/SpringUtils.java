package w.util;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import w.Global;

/**
 * @author Frank
 * @date 2023/12/21 11:26
 */
public class SpringUtils {
    @Getter
    static ClassLoader springBootClassLoader;

    @Getter
    static Object springBootApplicationContext;

    static final String APP_CTX_CLASS_NAME = "org.springframework.context.ApplicationContext";

    public static boolean isSpring() {
        return springBootApplicationContext != null;
    }

    public static String getAppCtxClassName() {
        return APP_CTX_CLASS_NAME;
    }


    public static void initFromLoadedClasses(Class<?>[] loadedClasses) {
        for (Class<?> c : loadedClasses) {
            // if it is a spring boot fat jar, the class loader will be LaunchedURLClassLoader, for spring boot >1 and <3
            if (c.getClassLoader() == null) continue;
            if (c.getName().equals(SpringUtils.getAppCtxClassName())) {
                Object[] instances = Global.getInstances(c);
                ClassLoader cl = c.getClassLoader();
                System.out.println("find springboot application context is loaded by " + cl);
                SpringUtils.springBootApplicationContext = instances[0];
                SpringUtils.springBootClassLoader = c.getClassLoader();
                break;
            }
        }
    }

    public static String generateSpringCtxCode() {
        if (!isSpring()) {
            return "";
        }
        return String.format("%s ctx = (%s) (%s).getSpringBootApplicationContext();\n",
                APP_CTX_CLASS_NAME, APP_CTX_CLASS_NAME, SpringUtils.class.getName());
    }
}
