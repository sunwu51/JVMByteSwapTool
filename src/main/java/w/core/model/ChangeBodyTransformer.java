package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import lombok.Data;

import org.codehaus.commons.compiler.CompileException;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import w.Global;
import w.core.compiler.WCompiler;
import w.core.constant.Codes;
import w.web.message.ChangeBodyMessage;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class ChangeBodyTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient ChangeBodyMessage message;

    String method;

    List<String> paramTypes;

    int mode;

    public ChangeBodyTransformer(ChangeBodyMessage message) {
        this.className = message.getClassName();
        this.method = message.getMethod();
        this.message = message;
        this.traceId = message.getId();
        this.paramTypes = message.getParamTypes();
        this.mode = message.getMode();
    }

    @Override
    public byte[] transform(byte[] origin) throws Exception {
        byte[] result = null;
        if (mode == Codes.changeBodyModeUseJavassist) {
            // use javassist, message.body is the method body, a code block starts with { ends with }
            result = changeBodyByJavassist(origin);
        } else if (mode == Codes.changeBodyModeUseASM) {
            // use asm, message.body is the whole method including signature, like `public void hi {}`
            result = changeBodyByASM(origin);
        }
//        new FileOutputStream("T.class").write(result);
        status = 1;
        return result;
    }

    private byte[] changeBodyByJavassist(byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        boolean effect = false;
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method) &&
                    Arrays.equals(paramTypes.toArray(new String[0]),
                            Arrays.stream(declaredMethod.getParameterTypes()).map(CtClass::getName).toArray())
            ) {
                if ((declaredMethod.getModifiers() & Modifier.ABSTRACT) != 0) {
                    throw new IllegalArgumentException("Cannot change abstract method.");
                }
                if ((declaredMethod.getModifiers() & Modifier.NATIVE) != 0) {
                    throw new IllegalArgumentException("Cannot change native method.");
                }
                declaredMethod.setBody(message.getBody());
                effect = true;
            }
        }
        if (!effect) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        byte[] result = ctClass.toBytecode();
        ctClass.detach();
        return result;
    }

    private byte[] changeBodyByASM(byte[] origin) throws Exception {

        boolean effect = false;
        String paramDes = paramTypesToDescriptor(paramTypes);

        ClassReader cr = new ClassReader(origin);
        ClassReader rcr = null;

        ClassNode targetClassNode = new ClassNode();
        cr.accept(targetClassNode, ClassReader.EXPAND_FRAMES);

        for (MethodNode mn : targetClassNode.methods) {
            // find target method
            if (mn.name.equals(method) && mn.desc.startsWith(paramDes)) {
                // compile replacement
                rcr = compileReplacement(mn);
                ClassNode replacementClassNode = new ClassNode();
                rcr.accept(replacementClassNode, 0);
                for (MethodNode rmn : replacementClassNode.methods) {
                    if (rmn.name.equals(method) && rmn.desc.startsWith(paramDes)) {
                        mn.instructions = rmn.instructions;
                        mn.tryCatchBlocks = rmn.tryCatchBlocks;
                        mn.localVariables = rmn.localVariables;
                        effect = true;
                        break;
                    }
                }
            }
        }
        if (!effect) {
            throw new IllegalArgumentException("Method not declared here.");
        }
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        targetClassNode.accept(classWriter);
        byte[] result = classWriter.toByteArray();
//        new FileOutputStream("T.class").write(result);
        return result;
    }

    public boolean equals(Object other) {
        if (other instanceof ChangeBodyTransformer) {
            return this.uuid.equals(((ChangeBodyTransformer) other).getUuid());
        }
        return false;
    }

    @Override
    public String desc() {
        return "ChangeBody_" + getClassName() + "#" + method + " " + paramTypes;
    }

    private ClassReader compileReplacement(MethodNode mn) {
        try {
            String descriptor = mn.desc;
            List<String> exceptions = mn.exceptions;
            StringBuilder m = new StringBuilder();
            m.append(Type.getReturnType(descriptor).getClassName()).append(" ").append(method).append("(");
            Type[] params = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < params.length; i++) {
                if (i != 0) m.append(", ");
                m.append(params[i].getClassName()).append(" ").append("$").append(i + 1);
            }
            m.append(")");

            if (exceptions != null && !exceptions.isEmpty()) {
                m.append("throws ");
                for (int i = 0; i < exceptions.size(); i++) {
                    if (i != 0) m.append(",");
                    m.append(exceptions.get(i).replace("/", "."));
                }
            }

            m.append(message.getBody());

            return new ClassReader(WCompiler.compileMethod(className, m.toString()));
        }  catch (CompileException e) {
            throw new IllegalArgumentException("Source code compile error", e);
        }
    }

}