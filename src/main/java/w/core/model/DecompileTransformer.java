package w.core.model;

import lombok.Data;
import w.Global;
import w.core.compiler.WCompiler;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import w.web.message.DecompileMessage;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;


/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class DecompileTransformer extends BaseClassTransformer {

    transient DecompileMessage message;

    ThreadLocal<Map<String, byte[]>> relatedClassesCtx = ThreadLocal.withInitial(HashMap::new);
    transient volatile DecompileResult decompileResult;
    transient volatile boolean retryRequired;
    transient volatile ClassLoader decompileClassLoader;

    public DecompileTransformer(DecompileMessage decompileMessage) {
        this.className = decompileMessage.getClassName();
        this.message = decompileMessage;
        this.traceId = decompileMessage.getId();
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        try {
            Map<String, byte[]> classes = relatedClassesCtx.get();
            WCompiler.DecompileResult compilerResult = WCompiler.decompileResult(
                    classes, className, decompileClassLoader);
            if (compilerResult.isRetryRequired()) {
                retryRequired = true;
                return origin;
            }
            DecompileResult result = new DecompileResult();
            result.setClassName(className);
            result.setSource(compilerResult.getSource());
            result.setLineMapping(compilerResult.getLineMapping());
            result.setInvocations(findInvocations(classes, compilerResult.getLineMapping()));
            decompileResult = result;
            Global.info("/* " + className + " source code: */\n" + result.getSource());
        } finally {
            relatedClassesCtx.remove();
        }
        return origin;
    }

    private List<DecompileResult.InvocationSite> findInvocations(Map<String, byte[]> classes, int[] mapping) {
        List<DecompileResult.InvocationSite> sites = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            final String declaringClass = entry.getKey();
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private int line = -1;

                        @Override
                        public void visitLineNumber(int line, Label start) {
                            this.line = line;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String innerName,
                                                    String innerDescriptor, boolean isInterface) {
                            DecompileResult.InvocationSite site = new DecompileResult.InvocationSite();
                            site.setDeclaringClass(declaringClass);
                            site.setOuterMethod(name);
                            site.setOuterDescriptor(descriptor);
                            site.setOriginalLine(line);
                            site.setDecompiledLine(mapLine(mapping, line));
                            site.setOpcode(opcodeName(opcode));
                            site.setOwner(owner.replace('/', '.'));
                            site.setName(innerName);
                            site.setDescriptor(innerDescriptor);
                            site.setInterfaceCall(isInterface);
                            sites.add(site);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return sites;
    }

    private Integer mapLine(int[] mapping, int originalLine) {
        if (mapping == null || originalLine < 0) {
            return null;
        }
        for (int i = 0; i + 1 < mapping.length; i += 2) {
            if (mapping[i] == originalLine) {
                return mapping[i + 1];
            }
        }
        return null;
    }

    private String opcodeName(int opcode) {
        switch (opcode) {
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            default: return "INVOKE";
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] origin) throws IllegalClassFormatException {
        if (className == null) {
            return origin;
        }
        className = className.replace("/", ".");
        if (className.startsWith(this.className + "$") && matchesTargetLoader(loader)) {
            relatedClassesCtx.get().put(className, origin);
            recordApplySuccess(loader, className);
            Global.info(className + " transformer " + uuid +  " added success <(^-^)>");
            return origin;
        } else if (Objects.equals(className, this.className) && matchesTargetLoader(loader)) {
            try{
                relatedClassesCtx.get().put(className, origin);
                decompileClassLoader = loader;
                byte[] r = transform(origin);
                recordApplySuccess(loader, className);
                Global.info(className + " transformer " + uuid +  " added success <(^-^)>");
                return r;
            } catch (Throwable e) {
                recordApplyFailure(loader, className, e);
                Global.error(className + " transformer " + uuid + " added fail -(′д｀)-: ", e);
            }
        }
        return null;
    }

    public boolean consumeRetryRequired() {
        boolean retry = retryRequired;
        retryRequired = false;
        return retry;
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

    @Override
    public boolean requiresRetransformOnDelete() {
        return false;
    }
}
