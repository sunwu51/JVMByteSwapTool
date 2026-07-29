package isolated;

public class ExecTarget {
    public String loaderIdentity() {
        return Integer.toHexString(System.identityHashCode(getClass().getClassLoader()));
    }
}
