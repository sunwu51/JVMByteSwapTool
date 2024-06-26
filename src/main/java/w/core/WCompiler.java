package w.core;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import w.Global;

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
}
