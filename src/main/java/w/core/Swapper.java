package w.core;

import w.Global;
import w.core.model.BaseClassTransformer;
import w.core.model.ChangeBodyTransformer;
import w.core.model.ChangeResultTransformer;
import w.core.model.DecompileTransformer;
import w.core.model.OuterWatchTransformer;
import w.core.model.ReplaceClassTransformer;
import w.core.model.SwapResult;
import w.core.model.TraceTransformer;
import w.core.model.TransformApplyResult;
import w.core.model.WatchTransformer;
import w.core.watch.WatchRuntime;
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;
import w.web.message.DecompileMessage;
import w.web.message.Message;
import w.web.message.OuterWatchMessage;
import w.web.message.ReplaceClassMessage;
import w.web.message.TraceMessage;
import w.web.message.WatchMessage;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public class Swapper {
    private static final Swapper INSTANCE = new Swapper();

    private Swapper() {}

    public static Swapper getInstance() {
        return INSTANCE;
    }

    public SwapResult swap(Message message) {
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
                case TRACE:
                    transformer = new TraceTransformer((TraceMessage) message);
                    break;
                case DECOMPILE:
                    transformer = new DecompileTransformer((DecompileMessage) message);
                    break;
                default:
                    Global.error("type not support");
                    throw new RuntimeException("message type not support");
            }
        } catch (Throwable e) {
            Global.error("build transform error:", e);
            return SwapResult.failure(message, "build transform error", e);
        }

        Set<Class<?>> classes;
        if (transformer instanceof WatchTransformer) {
            WatchMessage watchMessage = (WatchMessage) message;
            WatchTransformer watchTransformer = (WatchTransformer) transformer;
            ClassTargetResolver.Resolution resolution;
            try {
                BootstrapBridgeInstaller.install(Global.instrumentation);
                WatchRuntime.initialize();
                ClassTargetResolver resolver = new ClassTargetResolver(Global.instrumentation);
                resolution = resolver.resolve(
                        watchTransformer.getClassName(), watchMessage.getClassLoaderHash());
                if (!resolution.isSuccess()
                        && !resolution.hasCandidates()
                        && isBlank(watchMessage.getClassLoaderHash())) {
                    try {
                        Class.forName(watchTransformer.getClassName(), true, Global.getClassLoader());
                        resolution = resolver.resolve(watchTransformer.getClassName(), null);
                    } catch (ClassNotFoundException ignored) {
                        // Preserve the resolver's structured "not loaded" failure.
                    }
                }
            } catch (Throwable e) {
                Global.error("watch bridge preflight error:", e);
                return SwapResult.failure(message, "watch bridge preflight failed", e);
            }
            if (!resolution.isSuccess()) {
                return SwapResult.failure(message, resolution.getError(), resolution.candidateMaps());
            }
            Class<?> target = resolution.getTarget().getType();
            String preflightError = watchPreflight(target, watchTransformer);
            if (preflightError != null) {
                return SwapResult.failure(message, preflightError, resolution.candidateMaps());
            }
            watchTransformer.targetClassLoader(target.getClassLoader());
            classes = new LinkedHashSet<>();
            classes.add(target);
        } else {
            classes = new LinkedHashSet<>(Global.allLoadedClasses.getOrDefault(
                    transformer.getClassName(), new HashSet<>()));
        }

        boolean classExists = false;
        for (Class<?> aClass : classes) {
            if (transformer instanceof DecompileTransformer) {
                // Decompile needn't check abstract
            } else if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                Set<String> candidates = new HashSet<>();
                for (Object instances : Global.getInstances(aClass)) {
                    candidates.add(instances.getClass().getName());
                }
                Global.error("!Error: Should use a simple pojo, but " + aClass.getName() +
                        " is a Interface or Abstract class or something wired, \nmaybe you should use: " + candidates);
                return SwapResult.failure(message, "should use a simple pojo, but " + aClass.getName()
                        + " is a Interface or Abstract class or something wired, maybe you should use: " + candidates);
            }
            classExists = true;
        }

        if (!classExists) {
            try {
                classes.add(Class.forName(transformer.getClassName(), true, Global.getClassLoader()));
            } catch (ClassNotFoundException e) {
                Global.error("Class not exist: " + transformer.getClassName());
                return SwapResult.failure(message, "Class not exist: " + transformer.getClassName(), e);
            }
        }

        if (transformer instanceof TraceTransformer) {
            classes.addAll(((TraceTransformer) transformer).prepareNestedTargets(classes));
        }
        if (transformer instanceof OuterWatchTransformer) {
            classes.addAll(((OuterWatchTransformer) transformer).prepareNestedTargets(classes));
        }

        boolean watchRegistered = false;
        try {
            if (transformer instanceof WatchTransformer) {
                WatchRuntime.register(((WatchTransformer) transformer).config());
                watchRegistered = true;
            }
            Global.addTransformer(transformer);
        } catch (Throwable e) {
            if (watchRegistered) {
                transformer.clear();
            }
            Global.error("add transformer error:", e);
            return SwapResult.failure(message, "add transformer error", e);
        }
        Global.debug("add transformer" + transformer.getUuid() +" finish, will retrans class");

        LinkedHashSet<Class<?>> finalClasses = new LinkedHashSet<>();
        // decompile also need to retransform inner.class
        if (transformer instanceof DecompileTransformer) {
            String outerClassName = transformer.getClassName();
            Global.allLoadedClasses.entrySet().stream()
                    .filter(entry -> isDecompileRelatedClass(outerClassName, entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(Global::isModifiableClass)
                    .forEach(finalClasses::add);
        }
        finalClasses.addAll(classes);

        List<TransformApplyResult> applyResults = new ArrayList<>();
        for (Class<?> aClass : finalClasses) {
            transformer.beginApply(aClass);
            try {
                Global.addActiveTransformer(aClass, transformer);
            } catch (Throwable e) {
                Global.error("re transformer error:", e);
                applyResults.add(TransformApplyResult.failure(aClass, transformer.getUuid(), e));
                Global.deleteTransformer(transformer.getUuid());
                return SwapResult.of(message, transformer, applyResults);
            }
            TransformApplyResult applyResult = transformer.getApplyResult(aClass);
            if (applyResult == null) {
                applyResult = TransformApplyResult.failure(aClass, transformer.getUuid(), "transformer was not called");
            }
            applyResults.add(applyResult);
        }

        SwapResult result = SwapResult.of(message, transformer, applyResults);
        if (!result.isSuccess() || transformer instanceof DecompileTransformer) {
            Global.deleteTransformer(transformer.getUuid());
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isDecompileRelatedClass(String outerClassName, String candidateClassName) {
        return candidateClassName.startsWith(outerClassName + "$")
                && !candidateClassName.contains("$$Lambda$");
    }

    private String watchPreflight(Class<?> target, WatchTransformer transformer) {
        try {
            if (!Global.isModifiableClass(target)) {
                return "Target class is not modifiable: " + target.getName();
            }
            if (target.isInterface() || Modifier.isAbstract(target.getModifiers())) {
                return "should use a simple pojo, but " + target.getName()
                        + " is a Interface or Abstract class or something wired";
            }
            boolean methodFound = false;
            for (Method declaredMethod : target.getDeclaredMethods()) {
                if (declaredMethod.getName().equals(transformer.getMethod())) {
                    methodFound = true;
                    break;
                }
            }
            if (!methodFound) {
                return "Method not declared here: " + target.getName() + "#" + transformer.getMethod();
            }
            Class<?> bootstrapBridge = Class.forName("w.bridge.WatchBridge", false, null);
            if (bootstrapBridge.getClassLoader() != null) {
                return "WatchBridge is not bootstrap-loaded";
            }
            bootstrapBridge.getMethod("enter", String.class);
            bootstrapBridge.getMethod("exit", Object.class, long.class, Object.class,
                    Object[].class, Object.class, Throwable.class);
            ClassLoader targetLoader = target.getClassLoader();
            Class<?> visibleBridge = Class.forName("w.bridge.WatchBridge", false, targetLoader);
            if (visibleBridge != bootstrapBridge) {
                return "Target ClassLoader resolves a different WatchBridge";
            }
        } catch (Throwable e) {
            return "Target ClassLoader cannot resolve bootstrap WatchBridge: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return null;
    }
}
