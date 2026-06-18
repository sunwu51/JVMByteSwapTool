package w.core;

import lombok.Data;
import w.Global;
import w.core.compiler.WCompiler;
import w.web.message.ReplaceClassMessage;

import java.lang.reflect.InvocationTargetException;
import java.util.Base64;
import java.util.HashMap;

/**
 * @author Frank
 * @date 2023/12/9 20:50
 */
@Data
public class ExecBundle {
    private static final String EXEC_CLASS = "w.Exec";
    static Object inst;
    private static ClassLoader instParentClassLoader;

    public static void invoke() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        ensureInstance();
        Global.info("start to invoke");
        inst.getClass().getDeclaredMethod("exec")
                .invoke(inst);
        Global.info("finish invoking");
    }

    private static synchronized void ensureInstance() {
        ClassLoader parent = w.Global.getClassLoader();
        if (inst != null && instParentClassLoader == parent) {
            return;
        }
        try {
            Class<?> c = new ExecClassLoader(parent).loadClass(EXEC_CLASS);
            inst = c.newInstance();
            instParentClassLoader = parent;
            Global.fillLoadedClasses();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void changeBodyAndInvoke(String body) throws Exception {
        ensureInstance();
        byte[] byteCode = WCompiler.compileWholeClass(body);
        ReplaceClassMessage replaceClassMessage = new ReplaceClassMessage();
        replaceClassMessage.setClassName(EXEC_CLASS);
        replaceClassMessage.setContent(Base64.getEncoder().encodeToString(byteCode));
        // remove the old transformer
        clear();
        if (Swapper.getInstance().swap(replaceClassMessage).isSuccess()) {
            invoke();
        }
    }

    private static void clear() {
        // remove the old transformer
        Global.activeTransformers
                .getOrDefault(EXEC_CLASS, new HashMap<>()).values().forEach(baseClassTransformers -> {
                    baseClassTransformers.forEach(transformer -> {
                        Global.instrumentation.removeTransformer(transformer);
                        Global.transformers.remove(transformer);
                    });
                });
        Global.activeTransformers
                .getOrDefault(EXEC_CLASS, new HashMap<>()).clear();
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
            try {
                byte[] bytes = WCompiler.compileWholeClass("package w; public class Exec { public void exec() {} }");
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
