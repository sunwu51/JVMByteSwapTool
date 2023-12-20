package m3.prettyobject;

import m3.prettyobject.formatter.Formatter;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class ReflectionFormatFactory implements FormatterFactory {
    private final Constructor<? extends Formatter> constructor;

    public ReflectionFormatFactory(Class<? extends Formatter> clazz) throws NoSuchMethodException {
        constructor = clazz.getConstructor(Object.class);
    }

    @Override
    public Formatter mkPrettyFormatter(Object obj) {
        try {
            return constructor.newInstance(obj);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
