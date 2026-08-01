package w.core.compiler;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import org.jetbrains.java.decompiler.main.decompiler.InMemoryDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.InMemoryDecompiler.InMemoryDecompileResult;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import w.Global;

import java.util.HashMap;
import java.util.Map;
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
            if (pureClassName.contains("##Lambda_")) {
                return false;
            }
            if (pureClassName.startsWith(entrypoint)) {
                if (!Global.allLoadedClasses.containsKey(pureClassName)) {
                    try {
                        Class.forName(pureClassName, true, classLoader);
                        needReDecompile.set(true);
                        Global.info("Try to load class:" + pureClassName);
                        return true;
                    } catch (Exception e) {
                        Global.error("Try to load class error, skip:" + pureClassName);
                    }
                }
            }
            needReDecompile.compareAndSet(false, false);
            return false;
        });

        if (needReDecompile.get()) {
            return new DecompileResult(null, null, true);
        }
        return new DecompileResult(result.getSource(), result.getLineMapping(), false);
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
