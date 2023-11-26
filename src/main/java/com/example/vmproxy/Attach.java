package com.example.vmproxy;

import com.example.vmproxy.web.Httpd;
import com.sun.tools.attach.*;
import com.sun.tools.jdi.VirtualMachineManagerImpl;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

/**
 * @author Frank
 * @date 2023/11/26 13:07
 */
public class Attach {

    static int HTTP_PORT = 16000;

    public static void main(String[] args) throws IOException, AttachNotSupportedException, AgentLoadException, AgentInitializationException {
        List<VirtualMachineDescriptor> jps = VirtualMachine.list();
        for (int i = 0; i< jps.size(); i++) {
            System.out.println(String.format("[%s] %s %s", i, jps.get(i).id(), jps.get(i).displayName()));
        }

        System.out.println("请出入序号>>>>>>>>>>>>>>");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            int index = scanner.nextInt();

            if (index < 0  || index >= jps.size()) continue;

            String pid = jps.get(index).id();

            VirtualMachine jvm = VirtualMachine.attach(pid);

            String agentJarPath = "C:\\Users\\sunwu\\Desktop\\code\\Vmproxy\\target\\vmproxy-0.0.1-SNAPSHOT.jar";

            String agentArgs = "hello";

            jvm.loadAgent(agentJarPath, agentArgs);
            while (true) {
                try {
                    new Httpd(HTTP_PORT).start(10000, false);
                    System.out.println("http启动完成，监听端口" + HTTP_PORT);
                    break;
                } catch (Exception e) {
                    System.out.println(HTTP_PORT + "端口占用， 尝试切换端口");
                    HTTP_PORT ++;
                }
            }
            break;
        }

    }
}
