package m3.prettyobject.formatter;

public class CharSequenceFormatter extends SimpleToStringFormatter {
    public CharSequenceFormatter(Object obj) {
        super(obj);
    }

    @Override
    public String format() {
        if (obj == null)
            return "null";
        else
            return String.format("%s\"%s\"",
                    obj instanceof String ? "" : obj.getClass().getSimpleName() + ": ",
                    obj);
    }
}
