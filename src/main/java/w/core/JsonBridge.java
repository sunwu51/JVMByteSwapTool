package w.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

public class JsonBridge {
    private static final JSONWriter.Feature[] FEATURES = {
            JSONWriter.Feature.PrettyFormat,
            JSONWriter.Feature.ReferenceDetection,
            JSONWriter.Feature.IgnoreErrorGetter,
            JSONWriter.Feature.LargeObject
    };

    public String toJson(Object obj) {
        return JSON.toJSONString(obj, FEATURES);
    }
}
