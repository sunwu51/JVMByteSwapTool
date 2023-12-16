package w.core;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import w.*;
import w.web.util.ClassUtils;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Swapper {
    private static final Swapper INSTANCE = new Swapper();

    private Swapper() {}

    public static Swapper getInstance() {
        return INSTANCE;
    }

    private synchronized ResultCode retransform(MethodId methodId, Callable<ClassFileTransformer> callable) throws Exception {
        Global.log(1, "Checking whether method exists...");
        try {
            ClassUtils.checkMethodExists(methodId.getClassName(), methodId.getMethod(), methodId.getParamTypes());
        } catch (NotFoundException e1) {
            Global.log(1, "Class or method not exits: " + e1);
            return ResultCode.CLASS_OR_METHOD_NOT_FOUND;
        }
        Global.log(1, "Check exist finish");
        Global.log(1, "Checking whether method is re-transformed...");
        String traceId = Global.methodId2TraceId.get(methodId);
        if (traceId != null) {
            Global.log(1, "Yes, removing the origin re-transformer...");
            Retransformer origin = Global.traceId2MethodId2Trans.getOrDefault(traceId, new HashMap<>()).remove(methodId);
            if (origin != null) Global.instrumentation.removeTransformer(origin.getClassFileTransformer());
            Global.log(1, "Removed the origin re-transformer...");
        }
        Global.log(1, "Check re-transformer finish");

        Global.log(1, "Prepare new re-transformer...");
        ClassFileTransformer transformer = callable.call();
        Global.instrumentation.addTransformer(transformer, true);

        Global.log(1, "Reload the class" + methodId.getClassName());

        List<Class> cls = Global.classToLoader.get().getOrDefault(methodId.className, new HashSet<>()).stream().flatMap(it -> {
            if (it == null) {
                Global.log(2, "cannot change this class loaded by System classLoader");
                return Stream.empty();
            }
            try {
                return Stream.of(it.loadClass(methodId.className));
            } catch (ClassNotFoundException e) {
                return Stream.empty();
            }
        }).collect(Collectors.toList());
        System.out.println("cls" + cls);
        for (Class c : cls) {
            Global.instrumentation.retransformClasses(c);
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
                        if (traceId != null) {
                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
                                    .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
                            Global.methodId2TraceId.put(methodId, traceId);
                        }
                        CtClass ctClass = getCtClass(loader, methodId);
                        CtMethod ctMethod = getCtMethod(ctClass, methodId);
                        ctMethod.setBody(body);
                        result =  ctClass.toBytecode();
                        Global.log(1, Thread.currentThread().getName() + "Change body success: " + loader + ", " + clssName + "#" + methodId.getMethod());
                    } catch (Exception e) {
                        e.printStackTrace();
                        Global.log(2, Thread.currentThread().getName() +  "Change body fail: " + loader + ", " + clssName + "#" + methodId.getMethod() + e.getMessage());
                    }
                    return result;
                }
                return classfileBuffer;
            }
        });
    }

    public ResultCode changeResult(MethodId methodId, MethodId innerMethodId, String body) throws Exception {
        String traceId = Global.traceIdCtx.get();
        return retransform(methodId, () -> new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String clssName,
                                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) throws IllegalClassFormatException {
                clssName = clssName.replace("/", ".");
                byte[] result = null;
                if (methodId.getClassName().equals(clssName)) {
                    try {
                        if (traceId != null) {
                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
                                    .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
                            Global.methodId2TraceId.put(methodId, traceId);
                        }
                        CtClass ctClass = getCtClass(loader, methodId);
                        CtMethod ctMethod = getCtMethod(ctClass, methodId);

                        ctMethod.instrument(new ExprEditor() {
                            public void edit(MethodCall m) throws CannotCompileException {
                                if (m.getMethodName().equals(innerMethodId.getMethod())) {
                                    if (innerMethodId.getClassName().equals("*") || innerMethodId.getClassName().equals(m.getClassName())) {
                                        w.Global.info("hit at " + m);
                                        m.replace("{"+body+"}");
                                    }
                                }
                            }
                        });
                        result =  ctClass.toBytecode();
                        ctClass.detach();
                        Global.log(1, Thread.currentThread().getName() + "Change body success: " + loader + ", " + clssName + "#" + methodId.getMethod() + "inner:" + innerMethodId.getClassName() +"#" +innerMethodId.getMethod());
                    } catch (Exception e) {
                        e.printStackTrace();
                        Global.log(2, Thread.currentThread().getName() +  "Change body fail: " + loader + ", " + clssName + "#" + methodId.getMethod() + "inner:" + innerMethodId.getClassName() +"#" +innerMethodId.getMethod()+ e.getMessage());
                    }
                }
                return result;
            }
        });
    }

    public ResultCode watch(MethodId methodId, boolean useJson) throws Exception {
        String traceId = Global.traceIdCtx.get();
        return retransform(methodId, () -> new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String clssName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                clssName = clssName.replace("/", ".");
                byte[] result = null;
                try {
                    if (clssName.equals(methodId.className)) {
                        if (traceId != null) {
                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
                                    .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
                            Global.methodId2TraceId.put(methodId, traceId);
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
                        Global.log(1, "Watch success: " + clssName + "#" + methodId.getMethod());
                    }
                } catch (Exception e) {
                    Global.log(2, "Watch fail: " + e.getMessage());
                }
                return result;
            }
        });
    }

    public ResultCode outerWatch(MethodId outerMethodId, MethodId innerMethodId, boolean useJson) throws Exception {
        String traceId = Global.traceIdCtx.get();
        return retransform(outerMethodId, () -> new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String clssName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                clssName = clssName.replace("/", ".");
                byte[] result = null;
                try {
                    if (clssName.equals(outerMethodId.className)) {
                        if (traceId != null) {
                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
                                    .put(outerMethodId, new Retransformer(RetransformType.CHANGE_BODY, this));
                            Global.methodId2TraceId.put(outerMethodId, traceId);
                        }
                        CtClass curClass = Global.classPool.getCtClass(clssName);
                        CtMethod ctMethod = getCtMethod(curClass, outerMethodId);

                        ctMethod.instrument(new ExprEditor() {
                            public void edit(MethodCall m) throws CannotCompileException {
                                if (m.getMethodName().equals(innerMethodId.getMethod())) {
                                    if (innerMethodId.getClassName().equals("*") || m.getClassName().equals(innerMethodId.getClassName())) {
                                        m.replace("{" +
                                                "long start = System.currentTimeMillis();" +
                                                "$_ = $proceed($$);" +
                                                "long duration = System.currentTimeMillis() - start;" +
                                                "String req = Arrays.toString($args);" +
                                                "String res = \"\" + $_;" +
                                                (useJson ? "try{" +
                                                        "req = w.Global.objectMapper.writeValueAsString($args);" +
                                                        "res = w.Global.objectMapper.writeValueAsString($_);" +
                                                        "}catch (Exception e) {req = \"convert json error\"; res=req;}" : "") +
                                                "w.Global.traceIdCtx.set(\"" + traceId + "\");" +
                                                "w.Global.info(\"line" + m.getLineNumber() + ",cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);" +
                                                "w.Global.socketCtx.remove();" +
                                                "}");
                                    }
                                }
                            }
                        });



                        result = curClass.toBytecode();
                        curClass.detach();
                        Global.log(1, "Watch success: out=" + clssName + "#" + outerMethodId.getMethod() + ", inner=" + innerMethodId.getClassName() + "#" + innerMethodId.getMethod());
                    }
                } catch (Exception e) {
                    Global.log(2, "Watch fail: " + e.getMessage());
                }
                return result;
            }
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
                        Global.log(1, "Change body success: " + clssName + "#" + methodId.getMethod());
                    }
                }
            } catch (Exception e) {
                Global.log(2, "Change body fail: "+ e.getMessage());
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
                        Global.log(2, String.format("Param not same %s!=%s", pc[i].getName(), methodId.paramTypes.get(i)));
                        continue out;
                    }
                }
                return ctMethod;
            }
        }
        throw new NotFoundException("method not found");
    }
}


