package w.util;

import javassist.LoaderClassPath;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import ognl.*;
import w.Global;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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


    public static void initFromLoadedClasses() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?>[] loadedClasses = Global.instrumentation.getAllLoadedClasses();
        Set<ClassLoader> classLoaders = new HashSet<>();
        for (Class<?> c : loadedClasses) {
            // if it is a spring boot fat jar, the class loader will be LaunchedURLClassLoader, for spring boot >1 and <3
            if (c.getClassLoader() == null) continue;
            if (classLoaders.add(c.getClassLoader())) Global.classPool.appendClassPath(new LoaderClassPath(c.getClassLoader()));
            if (c.getName().equals(SpringUtils.getAppCtxClassName())) {
                Object[] instances = Global.getInstances(c);
                int max = -1;
                Object leader = null;
                for (Object instance : instances) {
                    int count = (int) instance.getClass().getMethod("getBeanDefinitionCount").invoke(instance);
                    if (count > max) {
                        max = count;
                        leader = instance;
                    }
                }
                ClassLoader cl = c.getClassLoader();
                System.out.println("find springboot application context is loaded by " + cl);
                SpringUtils.springBootApplicationContext = leader;
                SpringUtils.springBootClassLoader = c.getClassLoader();
                break;
            }
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
    }

    public static String generateSpringCtxCode() {
        if (!isSpring()) {
            return "";
        }
        return String.format("%s ctx = (%s) (%s).getSpringBootApplicationContext();\n",
                APP_CTX_CLASS_NAME, APP_CTX_CLASS_NAME, SpringUtils.class.getName());
    }

}