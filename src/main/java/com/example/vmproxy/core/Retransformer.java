package com.example.vmproxy.core;

import com.example.vmproxy.Global;
import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

public class Retransformer {
    private static final Retransformer INSTANCE = new Retransformer();

    private Retransformer() {}

    public static Retransformer getInstance() {
        return INSTANCE;
    }

    public Map<MethodId, ClassFileTransformer> retransformMethods = new HashMap<>();

    private synchronized ResultCode retransform(MethodId methodId, Callable<ClassFileTransformer> callable) throws Exception {
        // 1 检查方法是否存在
        Global.info("准备检查方法是否存在...");
        try {
            Utils.checkMethodExists(methodId.getClassName(), methodId.getMethod(), methodId.getParamTypes());
        } catch (NoClassException e1) {
            Global.info("类不存在 " + methodId.getClassName());
            return ResultCode.CLASS_NOT_FOUND;
        } catch (Exception e1) {
            Global.info("方法不存在 " + methodId.getMethod());
            return ResultCode.METHOD_NOT_FOUND;
        }
        Global.info("检查完毕，方法存在");

        // 2 检查该方法是否已经篡改
        Global.info("准备检查是否已经置换过...");
        boolean retransformed = retransformMethods.containsKey(methodId);
        if (retransformed) {
            Global.info("删除之前的置换器");
            Global.instrumentation.removeTransformer(retransformMethods.get(methodId));
        }
        Global.info("检查置换器完毕");

        // 3 构造新的置换器
        Global.info("准备设置新的置换器");
        ClassFileTransformer transformer = callable.call();

        // 4 将置换器注入
        retransformMethods.put(methodId, transformer);
        Global.instrumentation.addTransformer(transformer, true);
        try {
            Global.info("准备用新置换器加载类");
            // 遍历加载的所有类，每个类只要继承自该类，都需要被重新加载，主要对watch使用
            for (Class c : Global.instrumentation.getAllLoadedClasses()) {
                Class parent = c;
                while (parent != Objects.class && parent != null) {
                    if (parent.getName().equals(methodId.getClassName())) {
                        Global.instrumentation.retransformClasses(Class.forName(methodId.getClassName()));
                        Global.info("新的类"+c.getName()+"加载完毕");
                    }
                    parent = parent.getSuperclass();
                }
            }
        } catch (ClassNotFoundException e1) {
            return ResultCode.CLASS_NOT_FOUND;
        }
        return ResultCode.SUCCESS;
    }

    public ResultCode changeBody(MethodId methodId, String body) throws Exception {
        return retransform(methodId, () -> (loader, clssName, classBeingRedefined, protectionDomain, classfileBuffer) -> {
            clssName = clssName.replace("/", ".");
            byte[] result = classfileBuffer;
            if (methodId.getClassName().equals(clssName)) {
                try {
                    CtClass ctClass = getCtClass(loader, methodId);
                    CtMethod ctMethod = getCtMethod(ctClass, methodId);
                    System.out.println("cls is loaded by " + loader);
                    ctMethod.setBody(body);
                    result =  ctClass.toBytecode();
                } catch (Exception e) {
                    Global.info("置换失败" + e.getMessage());
                    throw new RuntimeException(e);
                }
                Global.info("置换成功");
                return result;
            }
            return classfileBuffer;
        });
    }

    public ResultCode watch(MethodId methodId) throws Exception {
        String traceId = Global.traceIdCtx.get();
        return retransform(methodId, () -> (loader, clssName, classBeingRedefined, protectionDomain, classfileBuffer) -> {
            clssName = clssName.replace("/", ".");
            byte[] result = classfileBuffer;
            try {
                CtClass curClass = ClassPool.getDefault().get(clssName);
                CtClass tarClass = ClassPool.getDefault().get(methodId.className);
                // 对于watch方法需要，修改所有的子类的方法
                if (curClass.subtypeOf(tarClass) && !curClass.isAnnotation() && !curClass.isInterface()) {
                    CtMethod ctMethod = getCtMethod(curClass, methodId);
                    // 如果添加不是基础类型的变量：ctMethod.addLocalVariable("str", classPool.get("java.lang.String"));
                    ctMethod.addLocalVariable("startTime", CtClass.longType);
                    ctMethod.addLocalVariable("endTime", CtClass.longType);
                    ctMethod.addLocalVariable("duration", CtClass.longType);
                    ctMethod.addLocalVariable("req", ClassPool.getDefault().get("java.lang.String"));
                    ctMethod.addLocalVariable("res", ClassPool.getDefault().get("java.lang.String"));
                    // 在方法的开头插入计时逻辑
                    ctMethod.insertBefore("startTime = System.currentTimeMillis();");
                    // 在方法的结尾插入计时逻辑
                    ctMethod.insertAfter("endTime = System.currentTimeMillis();");
                    ctMethod.insertAfter("duration = endTime - startTime;");
                    ctMethod.insertAfter("req = com.example.vmproxy.Global.objectMapper.writeValueAsString($args);");
                    ctMethod.insertAfter("res = com.example.vmproxy.Global.objectMapper.writeValueAsString($_);");
                    ctMethod.insertAfter("com.example.vmproxy.Global.traceIdCtx.set(\"" + traceId + "\");");
                    ctMethod.insertAfter("com.example.vmproxy.Global.info(\"cost:\"+duration+\"ms,req:\"+req+\",res:\"+res);");
                    ctMethod.insertAfter("com.example.vmproxy.Global.socketCtx.remove();");
                    result = curClass.toBytecode();
                }
            } catch (Exception e) {
                Global.info("置换失败"+ e.getMessage());
                throw new RuntimeException(e);
            }
            Global.info("置换成功");
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


