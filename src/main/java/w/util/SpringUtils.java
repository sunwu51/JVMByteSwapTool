package w.util;

import javassist.LoaderClassPath;
import lombok.Getter;
import w.Global;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Frank
 * @date 2023/12/21 11:26
 */
public class SpringUtils {
    @Getter
    static volatile ClassLoader springBootClassLoader;

    @Getter
    static volatile Object springBootApplicationContext;
    static volatile int springBootApplicationContextBeanCount = -1;

    static final String APP_CTX_CLASS_NAME = "org.springframework.context.ApplicationContext";
    private static final Set<ClassLoader> appendedClassLoaders = ConcurrentHashMap.newKeySet();

    public static boolean isSpring() {
        return springBootApplicationContext != null;
    }

    public static String getAppCtxClassName() {
        return APP_CTX_CLASS_NAME;
    }


    public static boolean initFromLoadedClasses() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?>[] loadedClasses = Global.instrumentation.getAllLoadedClasses();
        Set<ClassLoader> classLoaders = new HashSet<>();
        Object leader = null;
        ClassLoader leaderClassLoader = null;
        int max = -1;
        for (Class<?> c : loadedClasses) {
            // if it is a spring boot fat jar, the class loader will be LaunchedURLClassLoader, for spring boot >1 and <3
            if (c.getClassLoader() == null) {
                continue;
            }
            if (classLoaders.add(c.getClassLoader()) && appendedClassLoaders.add(c.getClassLoader())) {
                Global.classPool.appendClassPath(new LoaderClassPath(c.getClassLoader()));
            }
            if (c.getName().equals(SpringUtils.getAppCtxClassName())) {
                Object[] instances = Global.getInstances(c);
                for (Object instance : instances) {
                    if (!isContextReady(instance)) {
                        continue;
                    }
                    int count = (int) instance.getClass().getMethod("getBeanDefinitionCount").invoke(instance);
                    if (count > max) {
                        max = count;
                        leader = instance;
                        leaderClassLoader = c.getClassLoader();
                    }
                }
            }
        }

        boolean updated = false;
        if (leader != null && shouldUpdateSpringContext(leader, max)) {
            SpringUtils.springBootApplicationContext = leader;
            SpringUtils.springBootClassLoader = leaderClassLoader;
            SpringUtils.springBootApplicationContextBeanCount = max;
            updated = true;
            System.out.println("find springboot application context is loaded by " + leaderClassLoader
                    + ", bean count: " + max);
        }


        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> inputArguments = runtimeMXBean.getInputArguments();
        String xverifyValue = null;
        for (String a : inputArguments) {
            if (a.startsWith("-Xverify:")) {
                xverifyValue = a.substring("-Xverify:".length());
                break;
            }
        }
        if (Objects.equals("none", xverifyValue)) {
            Global.nonVerifying = true;
        }
        return updated;
    }

    private static boolean shouldUpdateSpringContext(Object leader, int beanCount) {
        Object current = SpringUtils.springBootApplicationContext;
        if (current == null) {
            return true;
        }
        if (!isContextReady(current)) {
            return true;
        }
        return leader != current && beanCount > SpringUtils.springBootApplicationContextBeanCount;
    }

    private static boolean isContextReady(Object instance) {
        try {
            return (Boolean) instance.getClass().getMethod("isActive").invoke(instance);
        } catch (NoSuchMethodException e) {
            return true;
        } catch (Exception e) {
            return false;
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
