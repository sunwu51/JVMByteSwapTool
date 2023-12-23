package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import lombok.Data;
import w.Global;
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class ChangeResultTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient ChangeResultMessage message;

    String method;

    List<String> paramTypes;

    String innerClassName;

    String innerMethod;

    public ChangeResultTransformer(ChangeResultMessage message) {
        this.setClassName(message.getClassName());
        this.method = message.getMethod();
        this.paramTypes = message.getParamTypes();
        this.innerMethod = message.getInnerMethod();
        this.innerClassName = message.getInnerClassName();
        this.message = message;
        this.traceId = message.getId();
    }

    @Override
    public byte[] transform(String className, byte[] origin) throws Exception {
        CtClass ctClass = Global.classPool.makeClass(new ByteArrayInputStream(origin));
        for (CtMethod declaredMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(declaredMethod.getName(), method) &&
                    Arrays.equals(paramTypes.toArray(new String[0]),
                            Arrays.stream(declaredMethod.getParameterTypes()).map(CtClass::getName).toArray())
            ) {
                declaredMethod.instrument(new ExprEditor() {
                    public void edit(MethodCall m) throws CannotCompileException {
                        if (m.getMethodName().equals(innerMethod)) {
                            if (Objects.equals(innerClassName, "*") || Objects.equals(innerClassName, m.getClassName())) {
                                m.replace("{"+message.getBody()+"}");
                            }
                        }
                    }
                });
            }
        }
        byte[] result = ctClass.toBytecode();
        ctClass.detach();
        status = 1;
        return result;
    }

    public boolean equals(Object other) {
        if (other instanceof ChangeResultTransformer) {
            return this.uuid.equals(((ChangeResultTransformer) other).getUuid());
        }
        return false;
    }
    @Override
    public String desc() {
        return "ChangeResult_" + getClassName() + "#" + method + " " + paramTypes;
    }
}
