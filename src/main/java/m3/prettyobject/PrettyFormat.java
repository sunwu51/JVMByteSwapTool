package m3.prettyobject;

import java.io.IOException;
import java.util.*;

public class PrettyFormat {
    private final PrettyFormatRegistry registry;
    private final int INDENT = 2;
    private final Set<Object> alreadyFormatted = new HashSet<Object>();
    private final Map<Integer, CharSequence> spaceCache = new HashMap<>();

    public PrettyFormat(PrettyFormatRegistry registry) {
        this.registry = registry;
    }

    public void format(Object obj, Appendable out) throws IOException {
        alreadyFormatted.clear();
        ArrayList<Object> objectPath = new ArrayList<Object>();
        formatHelper(objectPath, 0, out, obj, true);
    }

    private void formatHelper(ArrayList<Object> objectPath, int indents, Appendable out, Object obj, boolean indentFirstLine) throws IOException {
        if (indentFirstLine) {
            out.append(spaces(indents));
        }
        if (obj == null) {
            out.append("null");
            return;
        }

        if (objectPath.contains(obj)) {
            out.append("Circular Reference: ");
            out.append(obj.toString());
            return;
        }

        FormatterFactory factory = registry.find(obj);
        m3.prettyobject.formatter.Formatter formatter = factory.mkPrettyFormatter(obj);

        objectPath.add(obj);
        Object preamble = formatter.getPreamble();
        if (preamble != null) {
            formatHelper(objectPath, indents, out, preamble, false);
        }

        if (formatter.isMultiline()) {
            Iterator<Object> iterator = formatter.getChildren().iterator();
            while (iterator.hasNext()) {
                Object child = iterator.next();
                out.append('\n');
                formatHelper(objectPath, indents + INDENT, out, child, true);
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append('\n');
            out.append(spaces(indents));
        } else {
            out.append(formatter.format());
        }

        Object postamble = formatter.getPostamble();
        if (postamble != null) {
            formatHelper(objectPath, indents, out, postamble, false);
        }
        objectPath.remove(obj);
    }

    private CharSequence spaces(int n) {
        // Reuse the CharSequence if one with the same size is already made.
        if (spaceCache.containsKey(n))
            return spaceCache.get(n);

        StringBuilder spaces = new StringBuilder();

        for (int i = 0; i < n; i++) {
            spaces.append(' ');
        }

        spaceCache.put(n, spaces);
        return spaces;
    }

}
