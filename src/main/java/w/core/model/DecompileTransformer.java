package w.core.model;

import lombok.Data;
import w.Global;
import w.core.compiler.WCompiler;
import w.web.message.DecompileMessage;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;


/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class DecompileTransformer extends BaseClassTransformer {

    transient DecompileMessage message;

    ThreadLocal<Map<String, byte[]>> relatedClassesCtx = ThreadLocal.withInitial(HashMap::new);

    public DecompileTransformer(DecompileMessage decompileMessage) {
        this.className = decompileMessage.getClassName();
        this.message = decompileMessage;
        this.traceId = decompileMessage.getId();
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        try {
            String sourceCode = WCompiler.decompile(relatedClassesCtx.get(), className);
            Global.info("/* " + className + " source code: */\n" + sourceCode);
        } finally {
            CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
            relatedClassesCtx.remove();
        }
        return origin;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        if (className == null) return origin;
        className = className.replace("/", ".");
        if (className .startsWith(this.className + "$")){
            Global.info(className + " transformer " + uuid +  " added success <(^-^)>");
            return relatedClassesCtx.get().put(className, origin);
        } else if (Objects.equals(className, this.className)) {
            try{
                byte[] r = transform(relatedClassesCtx.get().put(className, origin));
                Global.info(className + " transformer " + uuid +  " added success <(^-^)>");
                return r;
            } catch (Exception e) {
                Global.error(className + " transformer " + uuid + " added fail -(′д｀)-: ", e);
                // async to delete, because current thread holds the class lock
                CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
            }
        }
        return null;
    }


    public boolean equals(Object other) {
        if (other instanceof DecompileTransformer) {
            return this.uuid.equals(((DecompileTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "Decompile_" + getClassName();
    }
}
