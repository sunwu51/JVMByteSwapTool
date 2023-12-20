package m3.prettyobject.formatter;

import m3.prettyobject.formatter.wrappers.Symbol;

import java.util.Collection;

public class CollectionFormatter implements Formatter {

    private final Collection obj;

    public CollectionFormatter(Object collection) {
        this.obj = (Collection) collection;
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
        return new Symbol(obj.getClass().getSimpleName() + " {");
    }

    @Override
    public Object getPostamble() {
        return new Symbol("}");
    }

    @Override
    public Iterable<Object> getChildren() {
        return obj;
    }

    @Override
    public int maxChildrenCount() {
        return obj.size();
    }
}
