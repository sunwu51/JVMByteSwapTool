package w.core.debug;

class DebugTarget extends DebugTargetParent {
    static String staticValue = "static";
    int instanceValue = 5;

    int calculate(int value) {
        int doubled = value * 2;
        return doubled + 1;
    }

    int callAndStore(int value) {
        int result = add(value, 2);
        return result;
    }

    int chainedCalls(int value) {
        return add(value, 1) + add(value, 2);
    }

    private int add(int left, int right) {
        return left + right;
    }

    int callFailure(int value) {
        int result = fail(value);
        return result;
    }

    Result buildResult(int value) {
        return new Result(value, "built");
    }

    static final class Result {
        final int value;
        final String label;

        Result(int value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    String echo(String value) {
        return value;
    }

    void consume(int value) {
        instanceValue = value;
    }

    int fail(int value) {
        throw new IllegalStateException("failure-" + value);
    }

    int sum(int count) {
        int result = 0;
        for (int index = 0; index < count; index++) {
            result += 1;
        }
        return result;
    }
}
