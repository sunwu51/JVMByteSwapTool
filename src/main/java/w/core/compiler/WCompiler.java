package w.core.compiler;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import org.jetbrains.java.decompiler.main.decompiler.InMemoryDecompiler;
import w.Global;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Frank
 * @date 2024/6/26 0:00
 */
public class WCompiler {

    // generate a compiler every time to avoid conflict
    private static SimpleCompiler getCompiler() {
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(Global.getClassLoader());
        return compiler;
    }

    /**
     * Compile a class
     * @param content the class content, must have only one class declaration.
     * @return
     * @throws CompileException
     */
    public static byte[] compileWholeClass(String content) throws CompileException {
        SimpleCompiler compiler = getCompiler();
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
        String simpleClassName = className.substring(className.lastIndexOf(".") +1);
        return compileWholeClass("package " + packageName +";\n import java.util.*;\n public class " + simpleClassName + " {" + methodContent + "}");
    }

    /**
     * Compile a method, wrapped in a Dynamic class.
     * @param content { some code; }
     * @return
     * @throws CompileException
     */
    public static byte[] compileDynamicCodeBlock(String reType, String content)  throws CompileException {
        return compileMethod("w.Dynamic", "public "+ reType +" replace()" + content);
    }


    public static String decompile(Map<String, byte[]> classes, String entrypoint) {
        Map<String, Object> defaultOpt = new HashMap<>();
        defaultOpt.put("cps", "1");
        defaultOpt.put("crp", "1");
        return decompileWithFernFlower(classes, entrypoint, defaultOpt);
    }

    private static String decompileWithFernFlower(Map<String, byte[]> classes, String entrypoint, Map<String, Object> options) {
        AtomicBoolean needReDecompile = new AtomicBoolean(false);

        String result = InMemoryDecompiler.decompileClass(classes, entrypoint, options, null, (innerClassName) -> {
            String pureClassName = innerClassName.replace(".class", "").replace("/", ".");
            if (pureClassName.startsWith(entrypoint)) {
                if (!Global.allLoadedClasses.containsKey(pureClassName)) {
                    try {
                        Global.getClassLoader().loadClass(pureClassName);
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
            Global.fillLoadedClasses();
            return "There are some inner class need to be loaded firstly, try again!";
        }
        return result;
    }
}