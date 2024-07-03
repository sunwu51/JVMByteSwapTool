package w.core;

import lombok.Data;
import w.Global;
import w.core.compiler.WCompiler;
import w.web.message.ReplaceClassMessage;

import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Base64;
import java.util.HashMap;

/**
 * @author Frank
 * @date 2023/12/9 20:50
 */
@Data
public class ExecBundle {
    static Object inst;

    static {
        try {
            Class<?> c = new ExecClassLoader(w.Global.getClassLoader()).loadClass("w.Exec");
            inst = c.newInstance();
            Global.fillLoadedClasses();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static final String EXEC_CLASS = "w.Exec";

    public static void invoke() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Global.info("start to invoke");
        inst.getClass().getDeclaredMethod("exec")
                .invoke(inst);
        Global.info("finish invoking");
    }

    public static void changeBodyAndInvoke(String body) throws Exception {
        byte[] byteCode = WCompiler.compileWholeClass(body);
        ReplaceClassMessage replaceClassMessage = new ReplaceClassMessage();
        replaceClassMessage.setClassName(EXEC_CLASS);
        replaceClassMessage.setContent(Base64.getEncoder().encodeToString(byteCode));
        // remove the old transformer
        clear();
        if (Swapper.getInstance().swap(replaceClassMessage)) {
            invoke();
        }
    }

    private static void clear() {
        // remove the old transformer
        Global.activeTransformers
                .getOrDefault("w.Exec", new HashMap<>()).values().forEach(baseClassTransformers -> {
                    baseClassTransformers.forEach(transformer -> {
                        Global.instrumentation.removeTransformer(transformer);
                        Global.transformers.remove(transformer);
                    });
                });
        Global.activeTransformers
                .getOrDefault("w.Exec", new HashMap<>()).clear();
    }

    public static class ExecClassLoader extends ClassLoader {
        public ExecClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.equals("w.Exec")) {
                return super.loadClass(name);
            }
            FileInputStream f;
            try {
                byte[] bytes = WCompiler.compileWholeClass("package w; public class Exec { public void exec() {} }");
                return defineClass("w.Exec", bytes, 0, bytes.length);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }
}

