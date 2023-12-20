package m3.prettyobject.formatter.wrappers;

public class KeyValue {
    final Object key;
    private int maxKeyLength;
    final Object value;

    public KeyValue(Object key, int maxKeyLength, Object value) {
        this.key = key;
        this.maxKeyLength = maxKeyLength;
        this.value = value;
    }

    public Object getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    @Override
    public String toString() {
        String fmt = "%" + getMaxKeyLength() + "s => %s";
        return String.format(fmt, key, value);
    }
}
