package m3.prettyobject.formatter;

import m3.prettyobject.formatter.wrappers.Symbol;

public class SimpleToStringFormatter implements Formatter {

    protected final Object obj;

    public SimpleToStringFormatter(Object obj) {
        this.obj = obj;
    }

    @Override
    public boolean isMultiline() {
        return false;
    }

    @Override
    public boolean isIndexed() {
        return false;
    }

    @Override
    public String format() {
        return String.valueOf(obj);
    }

    @Override
    public Symbol getPreamble() {
        return null;
    }

    @Override
    public Object getPostamble() {
        return null;
    }

    @Override
    public Iterable<Object> getChildren() {
        return null;
    }

    @Override
    public int maxChildrenCount() {
        return 0;
    }
}
