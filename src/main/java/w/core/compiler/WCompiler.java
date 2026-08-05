package w.core.compiler;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import org.jetbrains.java.decompiler.main.decompiler.InMemoryDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.InMemoryDecompiler.InMemoryDecompileResult;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import w.Global;
import w.util.IOUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Frank
 * @date 2024/6/26 0:00
 */
public class WCompiler {

    // generate a compiler every time to avoid conflict
    private static SimpleCompiler getCompiler() {
        return getCompiler(Global.getClassLoader());
    }

    private static SimpleCompiler getCompiler(ClassLoader parentClassLoader) {
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(parentClassLoader);
        return compiler;
    }

    /**
     * Compile a class
     * @param content the class content, must have only one class declaration.
     * @return
     * @throws CompileException
     */
    public static byte[] compileWholeClass(String content) throws CompileException {
        return compileWholeClass(content, Global.getClassLoader());
    }

    public static byte[] compileWholeClass(String content, ClassLoader parentClassLoader) throws CompileException {
        SimpleCompiler compiler = getCompiler(parentClassLoader);
        compiler.cook(content);
        // only one class will be compiled
        String className = compiler.getBytecodes().keySet().iterator().next();
        return compiler.getBytecodes().get(className);
    }

    /**
     * Compile a method
     * @param className      the wrapper class name
     * @param methodContent  the method content, like <code>public void foo(){ ...}</code>
     * @return
     * @throws CompileException
     */
    public static byte[] compileMethod(String className, String methodContent) throws CompileException {
        String packageName = className.substring(0, className.lastIndexOf("."));
        String simpleClassName = className.substring(className.lastIndexOf(".") + 1);
        return compileWholeClass("package " + packageName + ";\n import java.util.*;\n public class " + simpleClassName + " {" + methodContent + "}");
    }

    /**
     * Compile a method, wrapped in a Dynamic class.
     * @param content { some code; }
     * @return
     * @throws CompileException
     */
    public static byte[] compileDynamicCodeBlock(String reType, String content) throws CompileException {
        return compileMethod("w.Dynamic", "public " + reType + " replace()" + content);
    }


    public static String decompile(Map<String, byte[]> classes, String entrypoint) {
        return decompileResult(classes, entrypoint).getSource();
    }

    public static DecompileResult decompileResult(Map<String, byte[]> classes, String entrypoint) {
        return decompileResult(classes, entrypoint, Global.getClassLoader());
    }

    public static DecompileResult decompileResult(Map<String, byte[]> classes, String entrypoint,
                                                  ClassLoader classLoader) {
        Map<String, Object> defaultOpt = new HashMap<>();
        defaultOpt.put("cps", "1");
        defaultOpt.put("crp", "1");
        defaultOpt.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
        return decompileWithFernFlower(classes, entrypoint, defaultOpt, classLoader);
    }

    private static DecompileResult decompileWithFernFlower(Map<String, byte[]> classes, String entrypoint,
                                                           Map<String, Object> options, ClassLoader classLoader) {
        AtomicBoolean needReDecompile = new AtomicBoolean(false);

        InMemoryDecompileResult result = InMemoryDecompiler.decompileClassResult(classes, entrypoint, options, null, (innerClassName) -> {
            String pureClassName = innerClassName.replace(".class", "").replace("/", ".");
            if (pureClassName.contains("##Lambda_") || !pureClassName.startsWith(entrypoint)) {
                return false;
            }
            return loadInnerClassChain(pureClassName, entrypoint, classLoader, needReDecompile);
        });

        if (needReDecompile.get()) {
            return new DecompileResult(null, null, true);
        }
        return new DecompileResult(result.getSource(), result.getLineMapping(), false);
    }

    /**
     * Load the requested inner class together with every nested anonymous class it references.
     * FernFlower only discovers the next nesting level on each pass, so a deeply nested chain
     * would otherwise exceed the retry limit and fail the decompile. Loading the whole chain in
     * one request lets the retry loop converge after a single pass.
     */
    private static boolean loadInnerClassChain(String rootClassName, String entrypoint,
                                               ClassLoader classLoader, AtomicBoolean needReDecompile) {
        Set<String> visited = new HashSet<>();
        return loadInnerClassChain0(rootClassName, entrypoint, classLoader, needReDecompile, visited);
    }

    private static boolean loadInnerClassChain0(String pureClassName, String entrypoint,
                                                ClassLoader classLoader, AtomicBoolean needReDecompile,
                                                Set<String> visited) {
        if (pureClassName.contains("##Lambda_") || !visited.add(pureClassName)) {
            return false;
        }
        boolean loadedAny = false;
        if (!Global.allLoadedClasses.containsKey(pureClassName)) {
            try {
                Class.forName(pureClassName, true, classLoader);
                needReDecompile.set(true);
                Global.info("Try to load class:" + pureClassName);
                loadedAny = true;
            } catch (Exception e) {
                Global.error("Try to load class error, skip:" + pureClassName);
                return false;
            }
        }
        for (String nested : findReferencedNestedClasses(pureClassName, entrypoint, classLoader)) {
            loadedAny |= loadInnerClassChain0(nested, entrypoint, classLoader, needReDecompile, visited);
        }
        return loadedAny;
    }

    private static List<String> findReferencedNestedClasses(String pureClassName, String entrypoint,
                                                            ClassLoader classLoader) {
        List<String> nested = new ArrayList<>();
        String resourceName = pureClassName.replace('.', '/') + ".class";
        String prefix = entrypoint + "$";
        InputStream inputStream = null;
        try {
            inputStream = classLoader == null
                    ? ClassLoader.getSystemResourceAsStream(resourceName)
                    : classLoader.getResourceAsStream(resourceName);
            if (inputStream == null) {
                return nested;
            }
            byte[] bytes = IOUtils.readAllBytes(inputStream);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.NEW && type != null) {
                                String referenced = type.replace("/", ".");
                                if (referenced.startsWith(prefix)
                                        && !referenced.contains("##Lambda_")
                                        && !referenced.contains("$$Lambda$")) {
                                    nested.add(referenced);
                                }
                            }
                            super.visitTypeInsn(opcode, type);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Throwable e) {
            Global.debug("scan nested classes error: " + pureClassName + ", " + e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
        }
        return nested;
    }

    public static final class DecompileResult {
        private final String source;
        private final int[] lineMapping;
        private final boolean retryRequired;

        public DecompileResult(String source, int[] lineMapping) {
            this(source, lineMapping, false);
        }

        public DecompileResult(String source, int[] lineMapping, boolean retryRequired) {
            this.source = source;
            this.lineMapping = lineMapping == null ? null : lineMapping.clone();
            this.retryRequired = retryRequired;
        }

        public String getSource() {
            return source;
        }

        public int[] getLineMapping() {
            return lineMapping == null ? null : lineMapping.clone();
        }

        public boolean isRetryRequired() {
            return retryRequired;
        }
    }
}
