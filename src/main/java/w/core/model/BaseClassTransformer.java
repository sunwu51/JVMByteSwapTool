package w.core.model;

import lombok.Getter;
import lombok.Setter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import w.Global;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author Frank
 * @date 2023/12/21 23:45
 */
@Getter
@Setter
public abstract class BaseClassTransformer implements ClassFileTransformer {
    protected UUID uuid = UUID.randomUUID();

    protected String className;

    protected String traceId;

    protected int status;



    public abstract byte[] transform(Class<?> className, byte[] origin) throws Exception;

    public abstract String desc();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        className = className.replace("/", ".");
        if (Objects.equals(this.className, className)) {
            try{
                byte[] r = transform(classBeingRedefined, origin);
                Global.info(className + " transformer " + uuid +  " added success <(^－^)>");
                return r;
            } catch (Exception e) {
                Global.error(className + " transformer " + uuid + " added fail -(′д｀)-: ", e);
                // async to delete, because current thread holds the class lock
                CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
            }
        }
        return null;
    }
}