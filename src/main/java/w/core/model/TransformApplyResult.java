package w.core.model;

import lombok.Data;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

@Data
public class TransformApplyResult {
    private UUID transformerId;
    private String className;
    private String classLoader;
    private boolean called;
    private boolean success;
    private String message;
    private String errorClass;
    private String stackTrace;

    public static TransformApplyResult pending(Class<?> clazz, UUID transformerId) {
        TransformApplyResult result = new TransformApplyResult();
        result.setTransformerId(transformerId);
        result.setClassName(clazz.getName());
        result.setClassLoader(classLoaderName(clazz.getClassLoader()));
        result.setCalled(false);
        result.setSuccess(false);
        result.setMessage("transformer was not called");
        return result;
    }

    public static TransformApplyResult success(ClassLoader loader, String className, UUID transformerId) {
        TransformApplyResult result = new TransformApplyResult();
        result.setTransformerId(transformerId);
        result.setClassName(className);
        result.setClassLoader(classLoaderName(loader));
        result.setCalled(true);
        result.setSuccess(true);
        result.setMessage("retransform success");
        return result;
    }

    public static TransformApplyResult failure(Class<?> clazz, UUID transformerId, Throwable e) {
        TransformApplyResult result = pending(clazz, transformerId);
        result.setMessage(e.getMessage());
        result.setErrorClass(e.getClass().getName());
        result.setStackTrace(stackTrace(e));
        return result;
    }

    public static TransformApplyResult failure(ClassLoader loader, String className, UUID transformerId, Throwable e) {
        TransformApplyResult result = new TransformApplyResult();
        result.setTransformerId(transformerId);
        result.setClassName(className);
        result.setClassLoader(classLoaderName(loader));
        result.setCalled(true);
        result.setSuccess(false);
        result.setMessage(e.getMessage());
        result.setErrorClass(e.getClass().getName());
        result.setStackTrace(stackTrace(e));
        return result;
    }

    public static TransformApplyResult failure(Class<?> clazz, UUID transformerId, String message) {
        TransformApplyResult result = pending(clazz, transformerId);
        result.setMessage(message);
        return result;
    }

    private static String classLoaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }

    private static String stackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
