package w.core.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.Global;
import w.web.message.DecompileMessage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;

public class DecompileTransformerTest {
    @Test
    public void decompileShouldReturnMappingAndInvocationSites() throws Exception {
        DecompileMessage message = new DecompileMessage();
        message.setClassName(SimpleDecompileTarget.class.getName());
        DecompileTransformer transformer = new DecompileTransformer(message);
        byte[] bytecode = classBytes(SimpleDecompileTarget.class);
        transformer.relatedClassesCtx.get().put(SimpleDecompileTarget.class.getName(), bytecode);
        Global.allLoadedClasses.put(SimpleDecompileTarget.class.getName(),
                Collections.<Class<?>>singleton(SimpleDecompileTarget.class));

        try {
            transformer.transform(bytecode);
        } finally {
            Global.allLoadedClasses.remove(SimpleDecompileTarget.class.getName());
        }

        DecompileResult result = transformer.getDecompileResult();
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.getSource().contains("class SimpleDecompileTarget"));
        Assertions.assertNotNull(result.getLineMapping());
        Assertions.assertTrue(result.getLineMapping().length > 0);
        Assertions.assertTrue(result.getInvocations().stream().anyMatch(site ->
                "normalize".equals(site.getOuterMethod())
                        && "trim".equals(site.getName())
                        && "java.lang.String".equals(site.getOwner())
                        && site.getDecompiledLine() != null));
    }

    private byte[] classBytes(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Assertions.assertNotNull(in);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}

class SimpleDecompileTarget {
    String normalize(String value) {
        return value.trim();
    }
}
