package w.core;

import javassist.*;
import w.*;
import w.web.util.ClassUtils;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class Swapper {
    private static final Swapper INSTANCE = new Swapper();

    private Swapper() {}

    public static Swapper getInstance() {
        return INSTANCE;
    }

    private synchronized ResultCode retransform(MethodId methodId, Callable<ClassFileTransformer> callable) throws Exception {
        Global.info("☕ Checking whether method exists...");
        try {
            ClassUtils.checkMethodExists(methodId.getClassName(), methodId.getMethod(), methodId.getParamTypes());
        } catch (NotFoundException e1) {
            Global.info("❌ Class or method not exits: " + e1);
            return ResultCode.CLASS_OR_METHOD_NOT_FOUND;
        }
        Global.info("✔\uFE0F Check exist finish");

        Global.info("☕ Checking whether method is re-transformed...");
        String traceId = Global.methodId2TraceId.get(methodId);
        if (traceId != null) {
            Global.info("✔\uFE0F Yes, removing the origin re-transformer...");
            Retransformer origin = Global.traceId2MethodId2Trans.getOrDefault(traceId, new HashMap<>()).remove(methodId);
            if (origin != null) Global.instrumentation.removeTransformer(origin.getClassFileTransformer());
            Global.info("✔\uFE0F Yes, removed the origin re-transformer...");
        }
        Global.info("✔\uFE0F Check re-transformer finish");

        Global.info("☕ Prepare new re-transformer...");
        ClassFileTransformer transformer = callable.call();
        Global.instrumentation.addTransformer(transformer, true);
        try {
            Global.info("☕ Reload the class" + methodId.getClassName());
            Global.instrumentation.retransformClasses(Thread.currentThread().getContextClassLoader().loadClass(methodId.getClassName()));
        } catch (ClassNotFoundException e1) {
            return ResultCode.CLASS_OR_METHOD_NOT_FOUND;
        }
        return ResultCode.SUCCESS;
    }

    public ResultCode changeBody(MethodId methodId, String body) throws Exception {
        String traceId = Global.traceIdCtx.get();
        return retransform(methodId, () -> new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String clssName,
                                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) throws IllegalClassFormatException {
                clssName = clssName.replace("/", ".");
                byte[] result = classfileBuffer;
                if (methodId.getClassName().equals(clssName)) {
                    try {
                        CtClass ctClass = getCtClass(loader, methodId);
                        CtMethod ctMethod = getCtMethod(ctClass, methodId);
                        ctMethod.setBody(body);
                        result =  ctClass.toBytecode();
                        ctClass.detach();
                        Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
                                .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
                        Global.methodId2TraceId.put(methodId, traceId);
                        Global.info("✔\uFE0F Change body success: " + clssName + "#" + methodId.getMethod());
                    } catch (Exception e) {
                        Global.info("❌ Change body fail: " + e.getMessage());
                    }
                    return result;
                }
                return classfileBuffer;
            }
        });
    }

    public ResultCode watch(MethodId methodId, boolean useJson) throws Exception {
        String traceId = Global.traceIdCtx.get();
        return retransform(methodId, () -> new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String clssName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                clssName = clssName.replace("/", ".");
                byte[] result = classfileBuffer;
                try {
                    if (clssName.equals(methodId.className)) {
                        Class origin = null;
                        for (Class c : Global.instrumentation.getAllLoadedClasses()) {
                            if (c.getName().equals(clssName)) {
                                origin = c;
                                Global.info("✔\uFE0F find loaded class with classLoader " + c.getClassLoader());
                                break;
                            }
                        }
                        if (origin != null) {
                            Global.classPool.appendClassPath(new LoaderClassPath(origin.getClassLoader()));
                        }
                        CtClass curClass = Global.classPool.getCtClass(clssName);
                        CtMethod ctMethod = getCtMethod(curClass, methodId);
                        ctMethod.addLocalVariable("startTime", CtClass.longType);
                        ctMethod.addLocalVariable("endTime", CtClass.longType);
                        ctMethod.addLocalVariable("duration", CtClass.longType);
                        ctMethod.addLocalVariable("req", ClassPool.getDefault().get("java.lang.String"));
                        ctMethod.addLocalVariable("res", ClassPool.getDefault().get("java.lang.String"));
                        ctMethod.insertBefore("startTime = System.currentTimeMillis();");
                        ctMethod.insertAfter("endTime = System.currentTimeMillis();");
                        ctMethod.insertAfter("duration = endTime - startTime;");
                        if (useJson) {
                            ctMethod.insertAfter("try {req = w.Global.objectMapper.writeValueAsString($args);} catch (Exception e) {req = \"convert json error\";}");
                            ctMethod.insertAfter("try {res = w.Global.objectMapper.writeValueAsString($_);} catch (Exception e) {res = \"convert json error\";}");
                        } else {
                            ctMethod.insertAfter("req = java.util.Arrays.toString($args);");
                            ctMethod.insertAfter("res = \"\" + $_;");
                        }
                        ctMethod.insertAfter("w.Global.traceIdCtx.set(\"" + traceId + "\");");
                        ctMethod.insertAfter("w.Global.info(\"cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);");
                        ctMethod.insertAfter("w.Global.socketCtx.remove();");
                        result = curClass.toBytecode();
                        curClass.detach();
                        Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
                                .put(methodId, new Retransformer(RetransformType.WATCH, this));
                        Global.methodId2TraceId.put(methodId, traceId);
                        Global.info("✔\uFE0F Watch success: " + clssName + "#" + methodId.getMethod());
                    }
                } catch (Exception e) {
                    Global.info("❌ Watch fail: " + e.getMessage());
                }
                return result;
            }
        });
    }

    public ResultCode changeExec(String body) throws Exception {
        MethodId methodId = new MethodId("w.Global", "exec", new ArrayList<>());
        return retransform(methodId, () -> (loader, clssName, classBeingRedefined, protectionDomain, classfileBuffer) -> {
            clssName = clssName.replace("/", ".");
            byte[] result = classfileBuffer;
            if (methodId.getClassName().equals(clssName)) {
                try {
                    CtClass ctClass = getCtClass(loader, methodId);
                    CtMethod ctMethod = getCtMethod(ctClass, methodId);
                    ctMethod.setBody("{" +
                            (Global.springApplicationContext != null ?
                                "org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext ctx = (org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext) w.Global.springApplicationContext;\n"
                                : "") + body +
                            "}");
                    result = ctClass.toBytecode();
                    ctClass.detach();
                    Global.info("✔\uFE0F Change execute method success");
                } catch (Exception e) {
                    Global.info("❌ Change execute method fail");
                }
            }
            return result;
        });
    }

    public ResultCode getSpringCtx() throws Exception {
        MethodId methodId = new MethodId("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter", "invokeHandlerMethod", null);
        return retransform(methodId, () -> (loader, clssName, classBeingRedefined, protectionDomain, classfileBuffer) -> {
            clssName = clssName.replace("/", ".");
            byte[] result = classfileBuffer;
            try {
                if (clssName.equals(methodId.className)) {
                    CtClass curClass = ClassPool.getDefault().get(clssName);
                    CtClass tarClass = ClassPool.getDefault().get(methodId.className);
                    // 对于watch方法需要，修改所有的子类的方法
                    if (curClass == tarClass) {
                        CtMethod ctMethod = getCtMethod(curClass, methodId);
                        ctMethod.insertAfter("{if (w.Global.springApplicationContext == null) {w.Global.springApplicationContext = $0.getApplicationContext();}}");
                        result = curClass.toBytecode();
                        Global.info("✔\uFE0F Change body success: " + clssName + "#" + methodId.getMethod());
                    }
                }
            } catch (Exception e) {
                Global.info("❌ Change body fail: "+ e.getMessage());
            }
            return result;
        });
    }

    private CtClass getCtClass(ClassLoader loader, MethodId methodId) throws NotFoundException {
        ClassPool classPool = ClassPool.getDefault();
        classPool.insertClassPath(new LoaderClassPath(loader));
        CtClass ctClass = classPool.getCtClass(methodId.getClassName());
        if (ctClass.isFrozen()) {
            ctClass.defrost();
        }
        return ctClass;
    }

    private CtMethod getCtMethod(CtClass ctClass, MethodId methodId) throws NotFoundException {
        out: for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            if (Objects.equals(ctMethod.getName(), methodId.getMethod())) {
                CtClass[] pc = ctMethod.getParameterTypes();
                if (methodId.getParamTypes() == null) {
                    return ctMethod;
                }
                if (pc.length != methodId.getParamTypes().size()) {
                    continue;
                }
                for (int i = 0; i< pc.length; i++) {
                    if (!pc[i].getName().equals(methodId.getParamTypes().get(i))) {
                        Global.info(String.format("同名方法，但是参数类型不一致。%s!=%s", pc[i].getName(), methodId.paramTypes.get(i)));
                        continue out;
                    }
                }
                return ctMethod;
            }
        }
        throw new NotFoundException("method not found");
    }
}


