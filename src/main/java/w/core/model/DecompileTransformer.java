package w.core.model;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import lombok.Data;
import org.objectweb.asm.*;
import w.Global;
import w.core.asm.WAdviceAdapter;
import w.core.compiler.WCompiler;
import w.web.message.DecompileMessage;
import w.web.message.WatchMessage;

import java.io.FileOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.objectweb.asm.Opcodes.ASM9;


/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class DecompileTransformer extends BaseClassTransformer {

    transient DecompileMessage message;

    public DecompileTransformer(DecompileMessage decompileMessage) {
        this.className = decompileMessage.getClassName();
        this.message = decompileMessage;
        this.traceId = decompileMessage.getId();
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        String sourceCode = WCompiler.decompile(origin);
        Global.info("/* " + className + " source code: */\n" + sourceCode);
        CompletableFuture.runAsync(() -> Global.deleteTransformer(uuid));
        return origin;
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
