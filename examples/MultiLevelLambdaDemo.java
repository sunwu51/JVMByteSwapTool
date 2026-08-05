import java.util.function.Supplier;

/**
 * Demonstrates that outerWatch and changeResult can observe/inject an inner call
 * buried multiple levels deep inside nested lambdas and anonymous inner classes.
 *
 * Usage:
 *   1. Compile and run:            java MultiLevelLambdaDemo
 *   2. Attach the agent jar:       java -jar target/swapper-0.0.21.jar <PID>
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
 *   6. Same-name calls from different owners (buildUserId):
 *      - outer_watch with innerSignature "*#getId" observes BOTH the
 *        MultiLevelLambdaDemo$User#getId and MultiLevelLambdaDemo$Product#getId
 *        call sites (two entries in data.inspectedCalls).
 *      - change_result with innerMethod "getId", innerClassName "*" FAILS with
 *        "Multi methods match ... which have different owner/desc".
 *      - change_result with innerClassName "MultiLevelLambdaDemo$User" only
 *        replaces the User#getId call and reports one enhancedCalls entry.
 */
public class MultiLevelLambdaDemo {

    static class User {
        private final int id;

        User(int id) {
            this.id = id;
        }

        int getId() {
            return id;
        }
    }

    static class Product {
        private final int id;

        Product(int id) {
            this.id = id;
        }

        int getId() {
            return id;
        }
    }

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

    /**
     * The innermost lambda calls two same-name methods from different owners:
     * MultiLevelLambdaDemo$User#getId and MultiLevelLambdaDemo$Product#getId.
     * Default result is 300 (100 + 200).
     */
    public int buildUserId() {
        User user = new User(100);
        Product product = new Product(200);
        Supplier<Integer> level1 = () -> {
            Supplier<Integer> level2 = () -> {
                Supplier<Integer> level3 = () -> user.getId() + product.getId();
                return level3.get();
            };
            return level2.get();
        };
        return level1.get();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Attach JVMByteSwapTool to PID " + ProcessHandle.current().pid());
        while (true) {
            MultiLevelLambdaDemo demo = new MultiLevelLambdaDemo();
            System.out.println("buildGreeting             = " + demo.buildGreeting());
            System.out.println("buildGreetingViaAnonymous = " + demo.buildGreetingViaAnonymous());
            System.out.println("buildUserId               = " + demo.buildUserId());
            System.out.println("--------------------------------------------");
            Thread.sleep(1_000L);
        }
    }
}
