package w;

import com.sun.tools.attach.*;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

/**
 * @author Frank
 * @date 2023/11/26 13:07
 */
public class Attach {

    public static void main(String[] args) throws IOException, AttachNotSupportedException, AgentLoadException, AgentInitializationException, URISyntaxException {

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
            jvm.loadAgent(curJarPath);
            jvm.detach();
        } catch (AgentLoadException e) {
            if (!Objects.equals(e.getMessage(), "0")) {
                throw e;
            }
        }
        System.out.println("============Attach finish");
    }
}
