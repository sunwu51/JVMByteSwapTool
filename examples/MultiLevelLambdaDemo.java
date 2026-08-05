import java.util.function.Supplier;

/**
 * Demonstrates that outerWatch and changeResult can observe/inject an inner call
 * buried multiple levels deep inside nested lambdas and anonymous inner classes.
 *
 * Usage:
 *   1. Compile and run:            java MultiLevelLambdaDemo
 *   2. Attach the agent jar:       java -jar target/swapper-0.0.20.jar <PID>
 *   3. outerWatch (observe greeting() inside buildGreeting's nested lambdas):
 *        outer_watch  { "signature": "MultiLevelLambdaDemo#buildGreeting",
 *                       "innerSignature": "MultiLevelLambdaDemo#greeting",
 *                       "includeNested": true, "logId": "outer-demo" }
 *      The runtime log then contains the greeting() call hit deep inside
 *      lambda$buildGreeting$N.
 *   4. changeResult (inject a different return value for the same buried call):
 *        change_result { "className": "MultiLevelLambdaDemo", "method": "buildGreeting",
 *                        "paramTypes": [], "innerClassName": "MultiLevelLambdaDemo",
 *                        "innerMethod": "greeting", "mode": 1,
 *                        "body": "$_ = \"injected by changeResult\";",
 *                        "includeNested": true }
 *      The demo loop then prints "injected by changeResult".
 *   5. The anonymous-class variant buildGreetingViaAnonymous works the same way,
 *      the call is inside a lambda inside an anonymous class.
 */
public class MultiLevelLambdaDemo {

    public String greeting() {
        return "hello, original greeting";
    }

    /**
     * greeting() is called 4 lambda levels deep. javac compiles each nested
     * lambda into a synthetic lambda$buildGreeting$N method in this same class.
     */
    public String buildGreeting() {
        Supplier<String> level1 = () -> {
            Supplier<String> level2 = () -> {
                Supplier<String> level3 = () -> {
                    Supplier<String> level4 = () -> greeting();
                    return level4.get();
                };
                return level3.get();
            };
            return level2.get();
        };
        return level1.get();
    }

    /**
     * greeting() is called inside a lambda that lives inside an anonymous class.
     * javac compiles it into MultiLevelLambdaDemo$1.lambda$get$N.
     */
    public String buildGreetingViaAnonymous() {
        Supplier<String> s = new Supplier<String>() {
            @Override
            public String get() {
                Supplier<String> inner = () -> {
                    Supplier<String> innerMost = () -> greeting();
                    return innerMost.get();
                };
                return inner.get();
            }
        };
        return s.get();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Attach JVMByteSwapTool to PID " + ProcessHandle.current().pid());
        while (true) {
            MultiLevelLambdaDemo demo = new MultiLevelLambdaDemo();
            System.out.println("buildGreeting             = " + demo.buildGreeting());
            System.out.println("buildGreetingViaAnonymous = " + demo.buildGreetingViaAnonymous());
            System.out.println("--------------------------------------------");
            Thread.sleep(1_000L);
        }
    }
}
