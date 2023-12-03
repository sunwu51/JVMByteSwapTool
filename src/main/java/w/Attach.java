package w;

import com.sun.tools.attach.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;

/**
 * @author Frank
 * @date 2023/11/26 13:07
 */
public class Attach {

    public static void main(String[] args) throws IOException, AttachNotSupportedException, AgentLoadException, AgentInitializationException {

        // Get the jvm process PID from args[0] or manual input
        // And get the spring http port from manual input
        String pid = null;
        int port = -1;
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
                    System.out.println(">>>>>>>>>>>>Please enter the serial number");
                    pid = String.valueOf(scanner.nextInt());
                    break;
                }
                pid = jps.get(index).id();
                break;
            }
        }

        System.out.printf("============The PID is %s%n", pid);
        System.out.println(">>>>>>>>>>>>Please enter the spring web server port, if not spring input enter key to skip");

        scanner.nextLine();
        String line = scanner.nextLine();

        if (line != null && !line.trim().isEmpty()) {
            try {
                port = Integer.parseInt(line.trim());
            } catch (Exception e) {
                System.err.println("port is not a integer");
                throw e;
            }
        }

        VirtualMachine jvm = VirtualMachine.attach(pid);
        File file = new File("swapper.jar");
        String agentJarPath = file.getAbsoluteFile().getPath();
        String agentArgs = String.format("port=%s", port);
        jvm.loadAgent(agentJarPath, agentArgs);
        jvm.detach();
        System.out.println("============Attach finish");
    }
}
