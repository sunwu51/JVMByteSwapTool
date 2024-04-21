package w.core;

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

}
