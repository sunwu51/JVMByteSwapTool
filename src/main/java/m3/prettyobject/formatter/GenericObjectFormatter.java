package m3.prettyobject.formatter;

import m3.prettyobject.formatter.wrappers.KeyValue;
import m3.prettyobject.formatter.wrappers.Symbol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

public class GenericObjectFormatter implements Formatter {
    private final Object obj;
    private ArrayList<Object> fieldList;

    public GenericObjectFormatter(Object obj) {
        this.obj = obj;
    }

    private boolean isNull() {
        return obj == null;
    }

    @Override
    public boolean isMultiline() {
        return !isNull() && !getFieldList().isEmpty();
    }

    @Override
    public boolean isIndexed() {
        return false;
    }

    @Override
    public String format() {
        return isNull() ? "null" : "";
    }

    @Override
    public Symbol getPreamble() {
        return isNull() ? null : new Symbol(obj.getClass().getName() + " {");
    }

    @Override
    public Object getPostamble() {
        return isNull() ? null : new Symbol("}");
    }

    @Override
    public Iterable<Object> getChildren() {
        return isNull() ? null : getFieldList();
    }

    @Override
    public int maxChildrenCount() {
        return isNull() ? 0 : obj.getClass().getFields().length;
    }

    private ArrayList<Object> getFieldList() {
        if (fieldList != null)
            return fieldList;

        ArrayList<Field> allFields = new ArrayList<Field>();
        getAllFields(allFields, obj.getClass());

        int maxKeyLength = 0;
        for (Field field:allFields) {
            maxKeyLength = Math.max(maxKeyLength, field.getName().length());
        }

        fieldList = new ArrayList<Object>();

        for (Field field:allFields) {
            try {
                field.setAccessible(true);
                fieldList.add(new KeyValue(new Symbol(field.getName()), maxKeyLength, field.get(obj)));
            } catch (IllegalAccessException e) {
                // this should never happen
                e.printStackTrace();
            }
        }

        return fieldList;
    }

    private void getAllFields(ArrayList<Field> allFields, Class<?> clazz) {
        if (clazz == null)
            return;

        Field[] fields = clazz.getDeclaredFields();
        for (Field field:fields) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
            allFields.add(field);
        }
        getAllFields(allFields, clazz.getSuperclass());
    }
}
