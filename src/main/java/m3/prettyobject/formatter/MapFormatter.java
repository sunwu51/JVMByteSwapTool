package m3.prettyobject.formatter;

import m3.prettyobject.formatter.wrappers.KeyValue;
import m3.prettyobject.formatter.wrappers.Symbol;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

public class MapFormatter implements Formatter {

    private final Map<Object, Object> map;

    public MapFormatter(Object map) {
        this.map = (Map<Object, Object>) map;
    }

    @Override
    public boolean isMultiline() {
        return true;
    }

    @Override
    public boolean isIndexed() {
        return true;
    }

    @Override
    public String format() {
        return null;
    }

    @Override
    public Symbol getPreamble() {
        return new Symbol(map.getClass() + " {");
    }

    @Override
    public Object getPostamble() {
        return new Symbol("}");
    }

    @Override
    public Iterable<Object> getChildren() {
        Set<Object> keys = map.keySet();
        int maxKeyLength = 0;
        for (Object key: keys) {
            maxKeyLength = Math.max(maxKeyLength, String.valueOf(key).length());
        }

        ArrayList<Object> keyValues = new ArrayList<Object>();

        for (Map.Entry e: map.entrySet()) {
            keyValues.add(new KeyValue(e.getKey(), maxKeyLength, e.getValue()));
        }

        return keyValues;
    }

    @Override
    public int maxChildrenCount() {
        return map.size();
    }
}
