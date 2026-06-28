package w.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.filter.Filter;
import com.alibaba.fastjson2.filter.PropertyPreFilter;
import com.alibaba.fastjson2.filter.ValueFilter;

public class JsonBridge {
    private static final String MAX_DEPTH_REACHED = "<max depth reached>";

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
        final int[] currentLevel = {-1};
        PropertyPreFilter levelTracker = (writer, object, name) -> {
            currentLevel[0] = writer.level();
            return true;
        };
        ValueFilter depthLimiter = (object, name, value) ->
                currentLevel[0] > maxDepth ? MAX_DEPTH_REACHED : value;
        return JSON.toJSONString(obj, new Filter[]{levelTracker, depthLimiter}, FEATURES);
    }
}
