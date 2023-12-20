package m3.prettyobject.formatter;

import m3.prettyobject.formatter.wrappers.Symbol;

public interface Formatter {
    boolean isMultiline();
    boolean isIndexed();
    String format();
    Object getPreamble();
    Object getPostamble();
    Iterable<Object> getChildren();
    int maxChildrenCount();
}
