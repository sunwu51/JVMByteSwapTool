package m3.prettyobject;

import m3.prettyobject.formatter.Formatter;

public interface FormatterFactory {
    Formatter mkPrettyFormatter(Object obj);
}
