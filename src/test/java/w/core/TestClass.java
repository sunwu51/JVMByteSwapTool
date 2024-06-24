package w.core;

import java.util.UUID;

/**
 * @author Frank
 * @date 2024/4/21 11:02
 */
public class TestClass {

    public String hello(String name) {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("hello " + name);
        return "hello " + name;
    }

    public String wrapperHello(String name) {
        try {
            return hello(name + "!");
        }catch (Exception e) {
            return "null";
        }
    }

    public String hello(String name, String arg2, String arg3) {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return "hello " + name + " " + arg2 + " " + arg3;
    }

    public double generateRandom() {
        double ran = Math.random();
        System.out.println("ran="+ ran);
        return ran;
    }

}
