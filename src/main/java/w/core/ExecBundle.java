package w.core;

import lombok.Data;
import w.Global;
import w.core.compiler.WCompiler;
import w.core.model.SwapResult;
import w.web.message.ReplaceClassMessage;

import java.lang.reflect.InvocationTargetException;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author Frank
 * @date 2023/12/9 20:50
 */
@Data
public class ExecBundle {
    private static final String EXEC_CLASS = "w.Exec";
    private static final Map<ClassLoader, ExecContext> CONTEXTS = new IdentityHashMap<>();

    public static void invoke() throws Exception {
        invoke(Global.getClassLoader());
    }

    public static void invoke(ClassLoader parent) throws Exception {
        synchronized (Global.class) {
            ExecContext context = ensureContext(parent);
            invoke(context);
        }
    }

    private static void invoke(ExecContext context)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Global.info("start to invoke");
        context.instance.getClass().getDeclaredMethod("exec").invoke(context.instance);
        Global.info("finish invoking");
    }

    private static ExecContext ensureContext(ClassLoader parent) {
        ExecContext context = CONTEXTS.get(parent);
        if (context != null) {
            return context;
        }
        try {
            ExecClassLoader classLoader = new ExecClassLoader(parent);
            Class<?> type = classLoader.loadClass(EXEC_CLASS);
            context = new ExecContext(classLoader, type.newInstance());
            CONTEXTS.put(parent, context);
            Global.fillLoadedClasses();
            return context;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void changeBodyAndInvoke(String body) throws Exception {
        changeBodyAndInvoke(body, Global.getClassLoader());
    }

    public static void changeBodyAndInvoke(String body, ClassLoader parent) throws Exception {
        synchronized (Global.class) {
            ExecContext context = ensureContext(parent);
            byte[] byteCode = WCompiler.compileWholeClass(body, context.classLoader);
            clear(context);

            ReplaceClassMessage message = new ReplaceClassMessage();
            message.setClassName(EXEC_CLASS);
            message.setClassLoaderHash(ClassTargetResolver.identityHash(context.classLoader));
            message.setContent(Base64.getEncoder().encodeToString(byteCode));
            SwapResult result = Swapper.getInstance().swap(message);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            context.transformerId = result.getTransformerId();
            invoke(context);
        }
    }

    private static void clear(ExecContext context) {
        UUID transformerId = context.transformerId;
        context.transformerId = null;
        if (transformerId != null) {
            Global.deleteTransformer(transformerId);
        }
    }

    static int contextCount() {
        synchronized (Global.class) {
            return CONTEXTS.size();
        }
    }

    public static void clearContexts() {
        synchronized (Global.class) {
            CONTEXTS.clear();
        }
    }

    private static final class ExecContext {
        private final ExecClassLoader classLoader;
        private final Object instance;
        private UUID transformerId;

        private ExecContext(ExecClassLoader classLoader, Object instance) {
            this.classLoader = classLoader;
            this.instance = instance;
        }
    }

    public static class ExecClassLoader extends ClassLoader {
        private final ClassLoader agentClassLoader;

        public ExecClassLoader(ClassLoader parent) {
            super(parent);
            this.agentClassLoader = ExecBundle.class.getClassLoader();
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.equals(EXEC_CLASS)) {
                if (isAgentApiClass(name)) {
                    return agentClassLoader.loadClass(name);
                }
                return super.loadClass(name);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try {
                byte[] bytes = WCompiler.compileWholeClass(
                        "package w; public class Exec { public void exec() {} }", this);
                return defineClass(EXEC_CLASS, bytes, 0, bytes.length);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private boolean isAgentApiClass(String name) {
            return name.equals("w.Global")
                    || name.startsWith("w.util.")
                    || name.startsWith("w.core.")
                    || name.startsWith("w.web.message.");
        }
    }
}
