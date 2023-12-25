package w.core.model;

import javassist.LoaderClassPath;
import lombok.Getter;
import lombok.Setter;
import w.Global;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.UUID;

/**
 * @author Frank
 * @date 2023/12/21 23:45
 */
@Getter
@Setter
public abstract class BaseClassTransformer implements ClassFileTransformer {
    private String className;

    protected String traceId;

    protected int status;

    protected UUID uuid = UUID.randomUUID();

    public void setClassName(String className) {
        for (Class<?> c : Global.instrumentation.getAllLoadedClasses()) {
            if (Objects.equals(c.getName(), className)) {
                Global.classPool.appendClassPath(new LoaderClassPath(c.getClassLoader()));
            }
        }
        this.className = className;
    }

    public abstract byte[] transform(String className, byte[] origin) throws Exception;

    public abstract String desc();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        className = className.replace("/", ".");
        if (Objects.equals(this.className, className)) {
            try{
                byte[] r = transform(className, origin);
                Global.info(className + " re transform by " + uuid +  " success <(^－^)>");
                return r;
            } catch (Exception e) {
                e.printStackTrace();
                Global.error(className + " re transform fail by " + uuid + " -(′д｀)-: " + e.getMessage());
            }
        }
        return null;
    }
}