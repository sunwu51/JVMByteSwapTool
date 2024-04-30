package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import lombok.Data;
import w.Global;
import w.web.message.ChangeBodyMessage;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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

    public ChangeBodyTransformer(ChangeBodyMessage message) {
        this.className = message.getClassName();
        this.method = message.getMethod();
        this.message = message;
        this.traceId = message.getId();
        this.paramTypes = message.getParamTypes();
    }

    @Override
    public byte[] transform(String className, byte[] origin) throws Exception {
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
        status = 1;
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
}
