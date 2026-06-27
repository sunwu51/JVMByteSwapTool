package w.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONWriter;

import java.lang.reflect.Field;

public class JsonBridge {
    private static final JSONWriter.Feature[] FEATURES = {
            JSONWriter.Feature.PrettyFormat,
            JSONWriter.Feature.ReferenceDetection,
            JSONWriter.Feature.IgnoreErrorGetter,
            JSONWriter.Feature.LargeObject
    };

    public String toJson(Object obj) {
        return toJson(obj, -1);
    }

    public String toJson(Object obj, int maxDepth) {
        if (maxDepth <= 0) {
            return JSON.toJSONString(obj, FEATURES);
        }
        JSONWriter.Context context = JSONFactory.createWriteContext(FEATURES);
        applyMaxDepth(context, maxDepth);
        try (JSONWriter writer = JSONWriter.of(context)) {
            writer.writeAny(obj);
            return writer.toString();
        }
    }

    private void applyMaxDepth(JSONWriter.Context context, int maxDepth) {
        try {
            Field field = JSONWriter.Context.class.getDeclaredField("maxLevel");
            field.setAccessible(true);
            field.setInt(context, maxDepth);
        } catch (Throwable ignored) {
            // Some fastjson2 versions do not expose maxLevel; keep default behavior.
        }
    }
}
