{{package_placeholder}}
import java.util.*;

public class InlineWrapper {

    static Object $$ = null;

    public static void replace({{args_placeholder}}) {
        {{body_placeholder}}
    }

    public static {{return_type}} $proceed() {
        throw new IllegalStateException("Placeholder cannot be invoked");
    }
}