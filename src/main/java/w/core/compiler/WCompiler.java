package w.core.compiler;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.state.ClassFileSourceImpl;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import w.Global;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

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
     * @param byteCode
     * @return
     */
    public static String decompile(byte[] byteCode) {
        StringBuilder sb = new StringBuilder();
        OutputSinkFactory outputSinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                return new ArrayList<SinkClass>() { { add(SinkClass.STRING); }};
            }
            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                return sinkable -> sb.append(sinkable.toString()).append('\n');
            }
        };
        CfrDriver driver = new CfrDriver.Builder()
                // fix: 中文显示为Unicode
                .withOptions(new HashMap<String, String>() {{
                    put("hideutf", "false");
                }})
                .withClassFileSource(new ClassFileSourceImpl(null) {
                    @Override
                    public Pair<byte[], String> getClassFileContent(String path) throws IOException {
                        if (path.equals("tmp.class")) {
                            return Pair.make(byteCode, path);
                        }
                        return null;
                    }
                })
                .withOutputSink(outputSinkFactory).build();
        List<String> tmp = new ArrayList<>();
        tmp.add("tmp.class");
        driver.analyse(tmp);
        String res = sb.toString();
        return res.substring(!res.contains(" */\n") ? 0 : res.indexOf(" */\n") + 3);
    }
}