package w.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import w.web.message.WatchMessage;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@AllArgsConstructor
@Data
public class WatchTransformer implements ClassFileTransformer {
    WatchMessage watchMessage;
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        return new byte[0];
    }
}
