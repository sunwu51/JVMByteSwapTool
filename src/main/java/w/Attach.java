package w;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import w.util.WClassLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

/**
 * @author Frank
 * @date 2023/11/26 13:07
 */
public class Attach {
    public static void main(String[] args) throws Exception {
        if (!Attach.class.getClassLoader().toString().startsWith(WClassLoader.namePrefix)) {
            String jdkVersion = System.getProperty("java.version");
            if (jdkVersion.startsWith("1.")) {
                if (jdkVersion.startsWith("1.8")) {
                    try {
                        // custom class loader to load current jar and tools.jar
                        WClassLoader customClassLoader = new WClassLoader(
                                new URL[]{toolsJarUrl(), currentUrl()},
                                ClassLoader.getSystemClassLoader().getParent()
                        );
                        Class<?> mainClass = Class.forName("w.Attach", true, customClassLoader);
                        Method mainMethod = mainClass.getMethod("main", String[].class);
                        mainMethod.invoke(null, (Object) args);
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.exit(-1);
                    }
                } else {
                    Global.error(jdkVersion + " is not supported");
                    return;
                }
            }
        }

        // Get the jvm process PID from args[0] or manual input
        // And get the spring http port from manual input
        String pid = null;
        Scanner scanner = new Scanner(System.in);

        if (args.length > 0) {
            pid = args[0].trim();
            try {
                Integer.parseInt(pid);
            } catch (Exception e) {
                System.err.println("The pid should be integer.");
                throw e;
            }
        } else {
            List<VirtualMachineDescriptor> jps = VirtualMachine.list();
            jps.sort(Comparator.comparing(VirtualMachineDescriptor::displayName));
            int i = 0;
            for (; i < jps.size(); i++) {
                System.out.printf("[%s] %s %s%n", i, jps.get(i).id(), jps.get(i).displayName());
            }
            System.out.printf("[%s] %s%n", i, "Custom PID");
            System.out.println(">>>>>>>>>>>>Please enter the serial number");

            while (true) {
                int index = scanner.nextInt();
                if (index < 0 || index > i) continue;
                if (index == i) {
                    System.out.println(">>>>>>>>>>>>Please enter the PID");
                    pid = String.valueOf(scanner.nextInt());
                    break;
                }
                pid = jps.get(index).id();
                break;
            }
        }
        System.out.printf("============The PID is %s%n", pid);
        VirtualMachine jvm = VirtualMachine.attach(pid);
        URL jarUrl = Attach.class.getProtectionDomain().getCodeSource().getLocation();
        String curJarPath = Paths.get(jarUrl.toURI()).toString();
        try {
            StringBuilder arg = new StringBuilder();
            System.getProperties().forEach((k, v) -> {
                if (k.toString().startsWith("w_") && k.toString().length() > 2) {
                    arg.append(k.toString().substring(2)).append("=").append(v.toString()).append("&");
                }
            });

            jvm.loadAgent(curJarPath, arg.toString());
            jvm.detach();
        } catch (Exception e) {
            if (!Objects.equals(e.getMessage(), "0")) {
                throw e;
            }
        }
        System.out.println("============Attach finish");
    }

    private static URL toolsJarUrl() throws Exception {
        String javaHome = System.getProperty("java.home");
        File toolsJarFile = new File(javaHome, "../lib/tools.jar");
        if (!toolsJarFile.exists()) {
            throw new Exception("tools.jar not found at: " + toolsJarFile.getPath());
        }
        URL toolsJarUrl = toolsJarFile.toURI().toURL();
        return toolsJarUrl;
    }

    private static URL currentUrl() throws Exception {
        ProtectionDomain domain = Attach.class.getProtectionDomain();
        CodeSource codeSource = domain.getCodeSource();
        return codeSource.getLocation();
    }
}
