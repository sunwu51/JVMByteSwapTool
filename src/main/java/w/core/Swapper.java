package w.core;

import w.Global;
import w.core.model.BaseClassTransformer;
import w.core.model.ChangeBodyTransformer;
import w.core.model.ChangeResultResult;
import w.core.model.ChangeResultTransformer;
import w.core.model.DecompileTransformer;
import w.core.model.OuterWatchResult;
import w.core.model.OuterWatchTransformer;
import w.core.model.ReplaceClassTransformer;
import w.core.model.SwapResult;
import w.core.model.TraceTransformer;
import w.core.model.TransformApplyResult;
import w.core.model.WatchTransformer;
import w.core.diagnostic.DiagnosticRuntime;
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;
import w.web.message.Message;
import w.web.message.ClassTargetMessage;
import w.web.message.DecompileMessage;
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
import java.util.stream.Collectors;


public class Swapper {
    private static final int MAX_DECOMPILE_RETRIES = 8;
    private static final Swapper INSTANCE = new Swapper();

    private Swapper() {}

    public static Swapper getInstance() {
        return INSTANCE;
    }

    public SwapResult swap(ClassTargetMessage message) {
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

        ClassTargetResolver.Resolution resolution;
        try {
            ClassTargetResolver resolver = new ClassTargetResolver(Global.instrumentation);
            resolution = resolver.resolve(transformer.getClassName(), message.getClassLoaderHash());
            if (!resolution.isSuccess()
                    && !resolution.hasCandidates()
                    && isBlank(message.getClassLoaderHash())) {
                try {
                    Class.forName(transformer.getClassName(), true, Global.getClassLoader());
                    resolution = resolver.resolve(transformer.getClassName(), null);
                } catch (ClassNotFoundException ignored) {
                    // Preserve the resolver's structured "not loaded" failure.
                }
            }
        } catch (Throwable e) {
            Global.error("target preflight error:", e);
            return SwapResult.failure(message, "target preflight failed", e);
        }
        if (!resolution.isSuccess()) {
            return SwapResult.failure(message, resolution.getError(), resolution.candidateMaps());
        }
        Class<?> target = resolution.getTarget().getType();
        transformer.targetClassLoader(target.getClassLoader());
        if (isDiagnosticTransformer(transformer)) {
            String preflightError = diagnosticPreflight(target);
            if (preflightError != null) {
                return SwapResult.failure(message, preflightError, resolution.candidateMaps());
            }
        }
        if (transformer instanceof WatchTransformer) {
            String preflightError = watchPreflight(target, (WatchTransformer) transformer);
            if (preflightError != null) {
                return SwapResult.failure(message, preflightError, resolution.candidateMaps());
            }
        }
        Set<Class<?>> classes = new LinkedHashSet<>();
        classes.add(target);

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
        if (transformer instanceof ChangeResultTransformer) {
            classes.addAll(((ChangeResultTransformer) transformer).prepareNestedTargets(classes));
        }

        boolean diagnosticRegistered = false;
        try {
            if (transformer instanceof WatchTransformer) {
                DiagnosticRuntime.register(((WatchTransformer) transformer).config());
                diagnosticRegistered = true;
            } else if (transformer instanceof OuterWatchTransformer) {
                DiagnosticRuntime.register(((OuterWatchTransformer) transformer).config());
                diagnosticRegistered = true;
            } else if (transformer instanceof TraceTransformer) {
                DiagnosticRuntime.register(((TraceTransformer) transformer).config());
                diagnosticRegistered = true;
            }
            Global.addTransformer(transformer);
        } catch (Throwable e) {
            if (diagnosticRegistered) {
                transformer.clear();
            }
            Global.error("add transformer error:", e);
            return SwapResult.failure(message, "add transformer error", e);
        }
        Global.debug("add transformer" + transformer.getUuid() +" finish, will retrans class");

        LinkedHashSet<Class<?>> finalClasses = transformer instanceof DecompileTransformer
                ? decompileClasses(transformer, classes)
                : new LinkedHashSet<>(classes);

        List<TransformApplyResult> applyResults = new ArrayList<>();
        if (!applyClasses(transformer, finalClasses, applyResults)) {
            Global.deleteTransformer(transformer.getUuid());
            SwapResult result = SwapResult.of(message, transformer, applyResults);
            attachTransformerData(result, transformer);
            return result;
        }

        if (transformer instanceof DecompileTransformer) {
            DecompileTransformer decompileTransformer = (DecompileTransformer) transformer;
            int previousClassCount = finalClasses.size();
            int retries = 0;
            while (decompileTransformer.consumeRetryRequired()) {
                if (retries++ >= MAX_DECOMPILE_RETRIES) {
                    applyResults.add(TransformApplyResult.failure(target, transformer.getUuid(),
                            "decompile exceeded " + MAX_DECOMPILE_RETRIES + " inner class retries"));
                    break;
                }
                Global.fillLoadedClasses();
                LinkedHashSet<Class<?>> retryClasses = decompileClasses(transformer, classes);
                if (retryClasses.size() <= previousClassCount) {
                    applyResults.add(TransformApplyResult.failure(target, transformer.getUuid(),
                            "decompile requested another inner class pass but no new class was loaded"));
                    break;
                }
                previousClassCount = retryClasses.size();
                if (!applyClasses(transformer, retryClasses, applyResults)) {
                    break;
                }
            }
        }

        SwapResult result = SwapResult.of(message, transformer, applyResults);
        attachTransformerData(result, transformer);
        result = enforceEnhancedCallSites(message, transformer, result);
        if (!result.isSuccess() || transformer instanceof DecompileTransformer) {
            Global.deleteTransformer(transformer.getUuid());
        }
        return result;
    }

    private boolean applyClasses(BaseClassTransformer transformer, Set<Class<?>> classes,
                                 List<TransformApplyResult> applyResults) {
        for (Class<?> aClass : classes) {
            transformer.beginApply(aClass);
            try {
                Global.addActiveTransformer(aClass, transformer);
            } catch (Throwable e) {
                Global.error("re transformer error:", e);
                applyResults.add(TransformApplyResult.failure(aClass, transformer.getUuid(), e));
                return false;
            }
            TransformApplyResult applyResult = transformer.getApplyResult(aClass);
            if (applyResult == null) {
                applyResult = TransformApplyResult.failure(aClass, transformer.getUuid(),
                        "transformer was not called");
            }
            applyResults.add(applyResult);
        }
        return true;
    }

    private LinkedHashSet<Class<?>> decompileClasses(BaseClassTransformer transformer,
                                                      Set<Class<?>> primaryClasses) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        String outerClassName = transformer.getClassName();
        Global.allLoadedClasses.entrySet().stream()
                .filter(entry -> isDecompileRelatedClass(outerClassName, entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(transformer::matchesTargetClassLoader)
                .filter(Global::isModifiableClass)
                .forEach(result::add);
        result.addAll(primaryClasses);
        return result;
    }

    private void attachTransformerData(SwapResult result, BaseClassTransformer transformer) {
        if (transformer instanceof DecompileTransformer) {
            result.setData(((DecompileTransformer) transformer).getDecompileResult());
        } else if (transformer instanceof OuterWatchTransformer) {
            result.setData(((OuterWatchTransformer) transformer).getOuterWatchResult());
        } else if (transformer instanceof ChangeResultTransformer) {
            result.setData(((ChangeResultTransformer) transformer).getResult());
        }
    }

    private SwapResult enforceEnhancedCallSites(Message message, BaseClassTransformer transformer, SwapResult result) {
        if (transformer instanceof OuterWatchTransformer) {
            OuterWatchResult watchResult = (OuterWatchResult) result.getData();
            if (watchResult == null || watchResult.getInspectedCalls().isEmpty()) {
                return SwapResult.failure(message, "no call sites matched innerSignature, nothing was instrumented");
            }
            Global.info("outer watch enhanced " + watchResult.getInspectedCalls().size() + " call site(s): line "
                    + watchResult.getInspectedCalls().stream()
                            .map(it -> String.valueOf(it.getLine()))
                            .collect(Collectors.joining(", ")));
        } else if (transformer instanceof ChangeResultTransformer) {
            ChangeResultResult changeResult = (ChangeResultResult) result.getData();
            if (changeResult == null || changeResult.getEnhancedCalls().isEmpty()) {
                return SwapResult.failure(message, "no call sites matched innerMethod, nothing was enhanced");
            }
            Global.info("change result enhanced " + changeResult.getEnhancedCalls().size() + " call site(s): line "
                    + changeResult.getEnhancedCalls().stream()
                            .map(it -> String.valueOf(it.getLine()))
                            .collect(Collectors.joining(", ")));
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isDiagnosticTransformer(BaseClassTransformer transformer) {
        return transformer instanceof WatchTransformer
                || transformer instanceof OuterWatchTransformer
                || transformer instanceof TraceTransformer;
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
        } catch (Throwable e) {
            return "watch preflight failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return null;
    }

    private String diagnosticPreflight(Class<?> target) {
        try {
            Class<?> bootstrapBridge = Class.forName("w.bridge.DiagnosticBridge", false, null);
            if (bootstrapBridge.getClassLoader() != null) {
                return "DiagnosticBridge is not bootstrap-loaded";
            }
            Class<?> visibleBridge = Class.forName(
                    "w.bridge.DiagnosticBridge", false, target.getClassLoader());
            if (visibleBridge != bootstrapBridge) {
                return "Target ClassLoader resolves a different DiagnosticBridge";
            }
        } catch (Throwable e) {
            return "Target ClassLoader cannot resolve bootstrap DiagnosticBridge: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return null;
    }
}
