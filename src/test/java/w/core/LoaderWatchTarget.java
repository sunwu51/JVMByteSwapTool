package w.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;

public class LoaderWatchTarget {
    public String echo(String value) {
        return "echo:" + value;
    }

    public String fail(String message) {
        throw new IllegalStateException(message);
    }

    public int recurse(int value) {
        return value <= 0 ? 0 : 1 + recurse(value - 1);
    }

    public String waitOn(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
        entered.countDown();
        release.await();
        return "released";
    }

    public LoaderWatchTarget badJson() {
        return this;
    }

    public String getBroken() {
        throw new IllegalArgumentException("broken getter");
    }

    public Object mergeTypes(boolean list) {
        Object value;
        if (list) {
            value = new ArrayList<>();
        } else {
            value = new HashSet<>();
        }
        return value;
    }
}
