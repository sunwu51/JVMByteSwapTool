package m3.prettyobject.formatter;

import m3.prettyobject.formatter.wrappers.Symbol;

import java.lang.reflect.Array;
import java.util.ArrayList;

public class ArrayFormatter implements Formatter {

    private final Object arr;

    public ArrayFormatter(Object arr) {
        if (!arr.getClass().isArray())
            throw new RuntimeException("object not an array");

        this.arr = arr;
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
        return new Symbol("[");
    }

    @Override
    public Object getPostamble() {
        return new Symbol("]");
    }

    @Override
    public Iterable<Object> getChildren() {
        int length = length();
        ArrayList<Object> items = new ArrayList<Object>(length);

        for (int i = 0; i < length; i++) {
            items.add(get(i));
        }

        return items;
    }

    private Object get(int i) {
        return Array.get(arr, i);
    }

    @Override
    public int maxChildrenCount() {
        return length();
    }

    private int length() {
        return Array.getLength(arr);
    }
}
