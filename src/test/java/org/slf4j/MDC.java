package org.slf4j;

import java.util.HashMap;
import java.util.Map;

public class MDC {
    private static final Adapter ADAPTER = new Adapter();
    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    public static Adapter getMDCAdapter() {
        return ADAPTER;
    }

    public static Map<String, String> getCopyOfContextMap() {
        return ADAPTER.getCopyOfContextMap();
    }

    public static void put(String key, String value) {
        Map<String, String> map = CONTEXT.get();
        if (map == null) {
            map = new HashMap<>();
            CONTEXT.set(map);
        }
        map.put(key, value);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static class Adapter {
        public Map<String, String> getCopyOfContextMap() {
            Map<String, String> map = CONTEXT.get();
            return map == null ? null : new HashMap<>(map);
        }
    }
}
