package w.core;

/**
 * @author Frank
 * @date 2024/4/30 19:29
 */
public interface MyInterface {
    default String interfaceDefaultMethod() {
        return "default";
    }

    String interfaceMethod();
}
