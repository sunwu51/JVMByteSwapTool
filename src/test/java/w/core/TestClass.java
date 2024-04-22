package w.core;

import java.util.UUID;

/**
 * @author Frank
 * @date 2024/4/21 11:02
 */
public class TestClass {

    public String hello(String name) {
        return "hello " + name;
    }

    public String wrapperHello(String name) {
        return hello(name);
    }


    public int recursive(int n) {
        if (n <= 1) return 1;
        UUID.randomUUID();
        return recursive(n - 1) + recursive(n - 2);
    }
}
