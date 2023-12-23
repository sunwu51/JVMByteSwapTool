package w.core;

import w.*;
import w.core.model.*;
import w.web.message.*;

import java.io.IOException;

import java.util.*;


public class Swapper {
    private static final Swapper INSTANCE = new Swapper();

    private Swapper() {}

    public static Swapper getInstance() {
        return INSTANCE;
    }

    public void swap(Message message) {
        BaseClassTransformer transformer = null;
        try {
            switch (message.getType()) {
                case WATCH:
                    transformer = new WatchTransformer((WatchMessage) message);
                    break;
                case OUTER_WATCH:
                    transformer = new OuterWatchTransformer((OuterWatchMessage) message);
                    break;
                case CHANGE_BODY:
                    transformer = new ChangeBodyTransformer((ChangeBodyMessage) message);
                    break;
                case CHANGE_RESULT:
                    transformer = new ChangeResultTransformer((ChangeResultMessage) message);
                    break;
                case REPLACE_CLASS:
                    transformer = new ReplaceClassTransformer((ReplaceClassMessage) message);
                    break;
                default:
                    Global.log(2, "type not support");
                    throw new RuntimeException("message type not support");
            }
        } catch (Throwable e) {
            e.printStackTrace();
            Global.log(2, "build transform error");
        }

        Global.instrumentation.addTransformer(transformer, true);
        Global.log(1, "add transform finish, will retrans class");

        for (Class<?> c : Global.instrumentation.getAllLoadedClasses()) {
            if (Objects.equals(c.getName(), transformer.getClassName()) && c.getClassLoader() != null) {
                try {
                    Global.instrumentation.retransformClasses(c);
                    Global.record(c, transformer);
                } catch (Throwable e) {
                    Global.log(2, "re transform error " + e.getMessage());
                }
            }
        }
    }

//    private synchronized ResultCode retransform(MethodId methodId, Callable<ClassFileTransformer> callable) throws Exception {
//        Global.log(1, "Prepare new re-transformer...");
//        ClassFileTransformer transformer = callable.call();
//        Global.instrumentation.addTransformer(transformer, true);
//
//        Global.log(1, "Reload the class" + methodId.getClassName());
//
//        List<Class> cls = Global.classToLoader.get().getOrDefault(methodId.className, new HashSet<>()).stream().flatMap(it -> {
//            if (it == null) {
//                Global.log(2, "cannot change this class loaded by System classLoader");
//                return Stream.empty();
//            }
//            try {
//                return Stream.of(it.loadClass(methodId.className));
//            } catch (ClassNotFoundException e) {
//                return Stream.empty();
//            }
//        }).collect(Collectors.toList());
//        try {
//            for (Class c : cls) {
//                Global.instrumentation.retransformClasses(c);
//            }
//        } catch (Throwable e) {
//            Global.log(2, "retrans error " + e.getMessage());
//        }
//        return ResultCode.SUCCESS;
//    }
//
//    public ResultCode changeBody(MethodId methodId, String body) throws Exception {
//        String traceId = Global.traceIdCtx.get();
//        return retransform(methodId, () -> new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String clssName,
//                                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
//                                    byte[] classfileBuffer) throws IllegalClassFormatException {
//                clssName = clssName.replace("/", ".");
//                byte[] result = null;
//                if (methodId.getClassName().equals(clssName)) {
//                    try {
//                        if (traceId != null) {
//                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
//                                    .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
//                            Global.methodId2TraceId.put(methodId, traceId);
//                        }
//                        CtClass ctClass = getCtClass(loader, methodId);
//                        CtMethod ctMethod = getCtMethod(ctClass, methodId);
//                        ctMethod.setBody(body);
//                        result =  ctClass.toBytecode();
//                        if (!methodId.className.equals("w.Exec")) {
//                            ctClass.detach();
//                        }
//                        Global.log(1, Thread.currentThread().getName() + "Change body success: " + loader + ", " + clssName + "#" + methodId.getMethod());
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                        Global.log(2, Thread.currentThread().getName() +  "Change body fail: " + loader + ", " + clssName + "#" + methodId.getMethod() + e.getMessage());
//                    }
//                }
//                return result;
//            }
//        });
//    }
//
//    public ResultCode changeResult(MethodId methodId, MethodId innerMethodId, String body) throws Exception {
//        String traceId = Global.traceIdCtx.get();
//        return retransform(methodId, () -> new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String clssName,
//                                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
//                                    byte[] classfileBuffer) throws IllegalClassFormatException {
//                clssName = clssName.replace("/", ".");
//                byte[] result = null;
//                if (methodId.getClassName().equals(clssName)) {
//                    try {
//                        if (traceId != null) {
//                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
//                                    .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
//                            Global.methodId2TraceId.put(methodId, traceId);
//                        }
//                        CtClass ctClass = getCtClass(loader, methodId);
//                        CtMethod ctMethod = getCtMethod(ctClass, methodId);
//
//                        ctMethod.instrument(new ExprEditor() {
//                            public void edit(MethodCall m) throws CannotCompileException {
//                                if (m.getMethodName().equals(innerMethodId.getMethod())) {
//                                    if (innerMethodId.getClassName().equals("*") || innerMethodId.getClassName().equals(m.getClassName())) {
//                                        w.Global.info("hit at " + m);
//                                        m.replace("{"+body+"}");
//                                    }
//                                }
//                            }
//                        });
//                        result =  ctClass.toBytecode();
//                        ctClass.detach();
//                        Global.log(1, Thread.currentThread().getName() + "Change body success: " + loader + ", " + clssName + "#" + methodId.getMethod() + "inner:" + innerMethodId.getClassName() +"#" +innerMethodId.getMethod());
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                        Global.log(2, Thread.currentThread().getName() +  "Change body fail: " + loader + ", " + clssName + "#" + methodId.getMethod() + "inner:" + innerMethodId.getClassName() +"#" +innerMethodId.getMethod()+ e.getMessage());
//                    }
//                }
//                return result;
//            }
//        });
//    }
//
//    public ResultCode watch(MethodId methodId, boolean useJson) throws Exception {
//        String traceId = Global.traceIdCtx.get();
//        return retransform(methodId, () -> new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String clssName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
//                clssName = clssName.replace("/", ".");
//                byte[] result = null;
//                try {
//                    if (clssName.equals(methodId.className)) {
//                        if (traceId != null) {
//                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
//                                    .put(methodId, new Retransformer(RetransformType.CHANGE_BODY, this));
//                            Global.methodId2TraceId.put(methodId, traceId);
//                        }
//                        CtClass curClass = Global.classPool.getCtClass(clssName);
//                        CtMethod ctMethod = getCtMethod(curClass, methodId);
//                        ctMethod.addLocalVariable("startTime", CtClass.longType);
//                        ctMethod.addLocalVariable("endTime", CtClass.longType);
//                        ctMethod.addLocalVariable("duration", CtClass.longType);
//                        ctMethod.addLocalVariable("req", ClassPool.getDefault().get("java.lang.String"));
//                        ctMethod.addLocalVariable("res", ClassPool.getDefault().get("java.lang.String"));
//                        ctMethod.insertBefore("startTime = System.currentTimeMillis();");
//                        ctMethod.insertAfter("endTime = System.currentTimeMillis();");
//                        ctMethod.insertAfter("duration = endTime - startTime;");
//                        if (useJson) {
//                            ctMethod.insertAfter("try {req = w.Global.toJson($args);} catch (Exception e) {req = \"convert json error\";}");
//                            ctMethod.insertAfter("try {res = w.Global.toJson($_);} catch (Exception e) {res = \"convert json error\";}");
//                        } else {
//                            ctMethod.insertAfter("req = w.Global.toString($args);");
//                            ctMethod.insertAfter("res = w.Global.toString($_);");
//                        }
//                        ctMethod.insertAfter("w.Global.traceIdCtx.set(\"" + traceId + "\");");
//                        ctMethod.insertAfter("w.Global.info(\"cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);");
//                        ctMethod.insertAfter("w.Global.socketCtx.remove();");
//                        result = curClass.toBytecode();
//                        curClass.detach();
//                        Global.log(1, "Watch success: " + clssName + "#" + methodId.getMethod());
//                    }
//                } catch (Exception e) {
//                    Global.log(2, "Watch fail: " + e.getMessage());
//                }
//                return result;
//            }
//        });
//    }
//
//    public ResultCode outerWatch(MethodId outerMethodId, MethodId innerMethodId, boolean useJson) throws Exception {
//        String traceId = Global.traceIdCtx.get();
//        return retransform(outerMethodId, () -> new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String clssName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
//                clssName = clssName.replace("/", ".");
//                byte[] result = null;
//                try {
//                    if (clssName.equals(outerMethodId.className)) {
//                        if (traceId != null) {
//                            Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k -> new ConcurrentHashMap<>())
//                                    .put(outerMethodId, new Retransformer(RetransformType.CHANGE_BODY, this));
//                            Global.methodId2TraceId.put(outerMethodId, traceId);
//                        }
//                        CtClass curClass = Global.classPool.getCtClass(clssName);
//                        CtMethod ctMethod = getCtMethod(curClass, outerMethodId);
//
//                        ctMethod.instrument(new ExprEditor() {
//                            public void edit(MethodCall m) throws CannotCompileException {
//                                if (m.getMethodName().equals(innerMethodId.getMethod())) {
//                                    if (innerMethodId.getClassName().equals("*") || m.getClassName().equals(innerMethodId.getClassName())) {
//                                        m.replace("{" +
//                                                "long start = System.currentTimeMillis();" +
//                                                "$_ = $proceed($$);" +
//                                                "long duration = System.currentTimeMillis() - start;" +
//                                                "String req = Arrays.toString($args);" +
//                                                "String res = \"\" + $_;" +
//                                                (useJson ? "try{" +
//                                                        "req = w.Global.toJson($args);" +
//                                                        "res = w.Global.toJson($_);" +
//                                                        "}catch (Exception e) {req = \"convert json error\"; res=req;}" : "") +
//                                                "w.Global.traceIdCtx.set(\"" + traceId + "\");" +
//                                                "w.Global.info(\"line" + m.getLineNumber() + ",cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);" +
//                                                "w.Global.socketCtx.remove();" +
//                                                "}");
//                                    }
//                                }
//                            }
//                        });
//
//                        result = curClass.toBytecode();
//                        curClass.detach();
//                        Global.log(1, "Watch success: out=" + clssName + "#" + outerMethodId.getMethod() + ", inner=" + innerMethodId.getClassName() + "#" + innerMethodId.getMethod());
//                    }
//                } catch (Exception e) {
//                    Global.log(2, "Out Watch fail: " + e.getMessage());
//                }
//                return result;
//            }
//        });
//    }
//
//
//    public ResultCode replaceClass(String clsName, byte[] content) throws Exception {
//        System.out.println(clsName);
//        String traceId = Global.traceIdCtx.get();
//        Global.log(1, "remove relate transformer");
//        synchronized (Global.class) {
//            Global.traceId2MethodId2Trans.values().forEach(map -> {
//                Set<MethodId> toDel = new HashSet<>();
//                for (MethodId methodId : map.keySet()) {
//                    if (methodId.className.equals(clsName)) {
//                        toDel.add(methodId);
//                        Global.instrumentation.removeTransformer(map.get(methodId).getClassFileTransformer());
//                    }
//                }
//                for (MethodId methodId : toDel) {
//                    map.remove(methodId);
//                }
//            });
//        }
//
//        Global.log(1, "add new transformer");
//        Global.instrumentation.addTransformer(new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
//                className = className.replace("/", ".");
//                if (className.equals(clsName)) {
//                    Global.traceId2MethodId2Trans.computeIfAbsent(traceId, k->new HashMap<>())
//                            .put(new MethodId(className, null, null), new Retransformer(RetransformType.REPLACE_CLASS, this));
//                    return content;
//                }
//                return null;
//            }
//        }, true);
//
//
//        try {
//            for (Class loadedClass : Global.instrumentation.getAllLoadedClasses()) {
//                if (loadedClass.getName().equals(clsName) && loadedClass.getClassLoader() != null) {
//                    Global.log(1, "retransform class");
//                    Global.instrumentation.retransformClasses(loadedClass);
//                }
//            }
//        } catch (Throwable e) {
//            Global.log(2, "retrans error " + e.getMessage());
//        }
//
//        return ResultCode.SUCCESS;
//    }
//
//    private CtClass getCtClass(ClassLoader loader, MethodId methodId) throws NotFoundException {
//        ClassPool classPool = ClassPool.getDefault();
//        classPool.insertClassPath(new LoaderClassPath(loader));
//        CtClass ctClass = classPool.getCtClass(methodId.getClassName());
//        if (ctClass.isFrozen()) {
//            ctClass.defrost();
//        }
//        return ctClass;
//    }
//
//    private CtMethod getCtMethod(CtClass ctClass, MethodId methodId) throws NotFoundException {
//        out: for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
//            if (Objects.equals(ctMethod.getName(), methodId.getMethod())) {
//                CtClass[] pc = ctMethod.getParameterTypes();
//                if (methodId.getParamTypes() == null) {
//                    return ctMethod;
//                }
//                if (pc.length != methodId.getParamTypes().size()) {
//                    continue;
//                }
//                for (int i = 0; i< pc.length; i++) {
//                    if (!pc[i].getName().equals(methodId.getParamTypes().get(i))) {
//                        Global.log(2, String.format("Param not same %s!=%s", pc[i].getName(), methodId.paramTypes.get(i)));
//                        continue out;
//                    }
//                }
//                return ctMethod;
//            }
//        }
//        throw new NotFoundException("method not found");
//    }
}


