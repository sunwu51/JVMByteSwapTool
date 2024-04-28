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
            CtClass ctClass = Global.classPool.makeClass("w.Exec");
            CtMethod ctMethod = CtMethod.make("public void exec() {}", ctClass);
            ctClass.addMethod(ctMethod);
            // use the spring boot class loader
            Class c = ctClass.toClass(Global.getClassLoader());
            ctClass.detach();
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
            String url = "http://localhost:" + Compiler.port  + "/compile";
            String urlParameters = "className=w.Exec&content=" + URLEncoder.encode(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));

            HttpURLConnection connection = null;

            try {
                URL obj = new URL(url);
                connection = (HttpURLConnection) obj.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("User-Agent", "Java client");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream())) {
                    wr.writeBytes(urlParameters);
                    wr.flush();
                }
                int responseCode = connection.getResponseCode();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String inputLine;
                    StringBuilder response = new StringBuilder();

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }

                    if (responseCode == HttpURLConnection.HTTP_OK) {

                        Map<String, Object> res = new ObjectMapper().readValue(response.toString(), new TypeReference<Map<String, Object>>() {
                        });

                        if ("0".equals(res.get("code").toString())) {
                            ReplaceClassMessage replaceClassMessage = new ReplaceClassMessage();
                            replaceClassMessage.setClassName("w.Exec");
                            replaceClassMessage.setContent(res.get("data").toString());
                            // remove the old transformer
                            clear();
                            if (Swapper.getInstance().swap(replaceClassMessage)) {
                                invoke();
                            }
                        } else {
                            Global.error(res.get("data"));
                        }
                    } else {
                        Global.error("compiler server return error : " + response);
                    }
                }
            } catch (Exception e) {
                Global.error("request compiler server error", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
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
}

