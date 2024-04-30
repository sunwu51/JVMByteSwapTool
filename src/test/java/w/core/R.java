package w.core;

import java.util.UUID;

/**
 * @author Frank
 * @date 2024/4/30 19:30
 */
public class R extends AbstractService {
    @Override
    String abstractParentMethod() {
        return "R.abstractParentMethod";
    }

    @Override
    public String interfaceMethod() {
        return "R.interfaceMethod";
    }

    public int recursive(int n) {
        if (n <= 1) return 1;
        UUID.randomUUID();
        return recursive(n - 1) + recursive(n - 2);
    }
}
