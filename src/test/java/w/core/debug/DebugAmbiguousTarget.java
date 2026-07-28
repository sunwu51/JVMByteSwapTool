package w.core.debug;

import java.util.function.IntUnaryOperator;

class DebugAmbiguousTarget {
    int calculate(int value) {
        IntUnaryOperator operation = current -> current + 1;
        return operation.applyAsInt(value);
    }
}
