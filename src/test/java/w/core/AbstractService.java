package w.core;

/**
 * @author Frank
 * @date 2024/4/30 19:26
 */
public abstract class AbstractService implements MyInterface {
    public String normalParentMethod() {
        return "a";
    }

    abstract String abstractParentMethod();
}
