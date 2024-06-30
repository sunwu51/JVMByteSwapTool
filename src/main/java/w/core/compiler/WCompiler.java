package w.core.compiler;

import com.strobel.assembler.metadata.ArrayTypeLoader;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import w.Global;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

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

    /**
     * Decompile a class
     * @param className
     * @param byteCode
     * @return
     */
    public static String decompile(String className, byte[] byteCode) {
        String desc = className.replace(".", "/");
        ArrayTypeLoader typeLoader = new ArrayTypeLoader(byteCode);
        DecompilerSettings settings = DecompilerSettings.javaDefaults();
        settings.setTypeLoader(typeLoader);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(outputStream)) {
            Decompiler.decompile(desc, new PlainTextOutput(writer), settings);
        }
        return outputStream.toString();
    }

    /**
     * original class file content
     * @param className
     * @return
     * @throws NotFoundException
     * @throws IOException
     * @throws CannotCompileException
     */
    public static String decompile(String className) throws NotFoundException, IOException, CannotCompileException {
        String desc = className.replace(".", "/");
        CtClass ctClass = Global.classPool.get(className);
        ArrayTypeLoader typeLoader = new ArrayTypeLoader(ctClass.toBytecode());
        DecompilerSettings settings = DecompilerSettings.javaDefaults();
        settings.setTypeLoader(typeLoader);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(outputStream)) {
            Decompiler.decompile(desc, new PlainTextOutput(writer), settings);
        }
        return outputStream.toString();
    }

}
