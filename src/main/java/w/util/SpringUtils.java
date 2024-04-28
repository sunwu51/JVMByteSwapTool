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

    @Getter
    static Set<String> classPathes = new HashSet<>();

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
                try {
                    unpackUberJar(c.getClassLoader());
                } catch (Exception e) {}
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

    private static void unpackUberJar(ClassLoader classLoader) {
        try {
            Map<String, Set<String>> innerInfo = new HashMap<>();
            Enumeration<URL> urls = classLoader.getResources("");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                // spring uber jar BOOT-INF/classes and BOOT-INF/lib/dependency.jar!/ ends with !/
                String[] parts = url.toString().split("!/");
                if (parts.length == 2 && url.toString().endsWith("!/")) {
                    String parentJar = parts[0].substring(parts[0].lastIndexOf(":") + 1);
                    String innerPath = parts[1];
                    innerInfo.computeIfAbsent(parentJar, k->new HashSet<>()).add(innerPath);
                }
            }
            String tempDir = System.getProperty("java.io.tmpdir");
            String targetDir = tempDir + "/" + System.currentTimeMillis() + "_classpath";
            innerInfo.forEach((jar, inners) -> {
                try {
                    classPathes.addAll(extractBootInfClasses(jar, targetDir, inners));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            classPathes.add(new File(targetDir).getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            Global.error("unpack uber jar failed, execute can only use javassist mode!");
        }

    }

    public static Set<String> extractBootInfClasses(String jarFilePath, String destDirPath, Set<String> inners) throws IOException {
        Set<String> result = new HashSet<>();
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                String innerName = null;
                for (String inner : inners) {
                    if (entryName.startsWith(inner)) {
                        innerName = inner;
                        break;
                    }
                }
                if (innerName != null) {
                    String prefix = "";
                    if (innerName.endsWith(".jar")) {
                        prefix = innerName.substring(0, innerName.lastIndexOf("/"));
                    } else {
                        prefix = innerName.endsWith("/") ? innerName : innerName + "/";
                    }
                    // directory for class files
                    if (entry.isDirectory()) {
                        continue;
                    }

                    Path targetPath = Paths.get(destDirPath + "/" + entryName.substring(prefix.length()));

                    if (Files.notExists(targetPath.getParent())) {
                        Files.createDirectories(targetPath.getParent());
                    }

                    try (InputStream is = jarFile.getInputStream(entry);
                         OutputStream os = Files.newOutputStream(targetPath)) {
                        copyStream(is, os);
                        result.add(targetPath.toFile().getAbsolutePath());
                    }
                }
            }
        }
        return result;
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }
}