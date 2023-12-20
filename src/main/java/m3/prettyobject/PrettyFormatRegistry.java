package m3.prettyobject;

import m3.prettyobject.formatter.*;
import m3.prettyobject.formatter.wrappers.KeyValue;
import m3.prettyobject.formatter.wrappers.Symbol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class PrettyFormatRegistry {
    Map<Class<?>, FormatterFactory> registry = new HashMap<Class<?>, FormatterFactory>();

    public static PrettyFormatRegistry createDefaultInstance() {
        PrettyFormatRegistry def = new PrettyFormatRegistry();

        try {
            def.register(Object.class, new ReflectionFormatFactory(GenericObjectFormatter.class));

            Class<?> primitiveClasses[] = {
                    Byte.TYPE,
                    Byte.class,
                    Short.TYPE,
                    Short.class,
                    Integer.TYPE,
                    Integer.class,
                    Long.TYPE,
                    Long.class,
                    Float.TYPE,
                    Float.class,
                    Double.TYPE,
                    Double.class,
                    Boolean.TYPE,
                    Boolean.class,
                    Character.TYPE,
                    Character.class
            };

            for (Class primitiveClass:primitiveClasses) {
                def.register(primitiveClass, new ReflectionFormatFactory(PrimitiveTypeFormatter.class));
            }

            def.register(Collection.class, new ReflectionFormatFactory(CollectionFormatter.class));
            def.register(Map.class, new ReflectionFormatFactory(MapFormatter.class));
            def.register(KeyValue.class, new ReflectionFormatFactory(KeyValueFormatter.class));
            def.register(Enum.class, new ReflectionFormatFactory(EnumFormatter.class));
            def.register(CharSequence.class, new ReflectionFormatFactory(CharSequenceFormatter.class));
            def.register(Symbol.class, new ReflectionFormatFactory(SymbolFormatter.class));
        } catch (NoSuchMethodException e) {
            // This should never happen
            e.printStackTrace();
        }

        return def;
    }

    public void register(Class<?> clazz, FormatterFactory factory) {
        registry.put(clazz, factory);
    }

    public FormatterFactory find(Object obj) {
        if (obj == null)
            return null;

        if (obj.getClass().isArray()) {
            try {
                return new ReflectionFormatFactory(ArrayFormatter.class);
            } catch (NoSuchMethodException e) {
                // This shouldn't happen
                throw new RuntimeException(e);
            }
        }

        Class<?> clazz = obj.getClass();
        return findByClass(clazz);
    }

    public FormatterFactory findByClass(Class<?> clazz) {
        if (registry.containsKey(clazz))
            return registry.get(clazz);
        else {

            Class<?>[] interfaces = clazz.getInterfaces();
            for (Class<?> intr:interfaces) {
                if (registry.containsKey(intr))
                    return registry.get(intr);
            }

            Class<?> superclass = clazz.getSuperclass();
            return superclass == null ? null : findByClass(superclass);
        }
    }

}
