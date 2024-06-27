package w.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javassist.CtClass;
import javassist.CtMethod;
import lombok.Data;
import w.Compiler;
import w.Global;
import w.util.SpringUtils;
import w.web.message.ChangeBodyMessage;
import w.web.message.ReplaceClassMessage;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

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
    public static void invoke() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Global.info("start to invoke");
        inst.getClass().getDeclaredMethod("exec")
                .invoke(inst);
        Global.info("finish invoking");
    }

    public static void changeBodyAndInvoke(int mode, String body) throws Exception {
        if (mode > 1 || mode < 0) return;

        if (mode == 0) {
            ChangeBodyMessage message = new ChangeBodyMessage();
            message.setClassName("w.Exec");
            message.setMethod("exec");
            message.setParamTypes(new ArrayList<>());
            body = "{" + SpringUtils.generateSpringCtxCode() + body + "}";
            message.setBody(body);
            // remove the old transformer
            clear();
            if (Swapper.getInstance().swap(message)) {
                invoke();
            }
        } else {
            byte[] byteCode = WCompiler.compileWholeClass(body);
            ReplaceClassMessage replaceClassMessage = new ReplaceClassMessage();
            replaceClassMessage.setClassName("w.Exec");
            replaceClassMessage.setContent(Base64.getEncoder().encodeToString(byteCode));
            // remove the old transformer
            clear();
            if (Swapper.getInstance().swap(replaceClassMessage)) {
                invoke();
            }
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

