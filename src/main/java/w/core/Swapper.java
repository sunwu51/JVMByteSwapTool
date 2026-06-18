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
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;
import w.web.message.DecompileMessage;
import w.web.message.Message;
import w.web.message.OuterWatchMessage;
import w.web.message.ReplaceClassMessage;
import w.web.message.TraceMessage;
import w.web.message.WatchMessage;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.ArrayList;
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

        Set<Class<?>> classes = Global.allLoadedClasses.getOrDefault(transformer.getClassName(), new HashSet<>());

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

        Global.addTransformer(transformer);
        Global.debug("add transformer" + transformer.getUuid() +" finish, will retrans class");

        LinkedHashSet<Class<?>> finalClasses = new LinkedHashSet<>();
        // decompile also need to retransform inner.class
        if (transformer instanceof DecompileTransformer) {
            String outerClassName = transformer.getClassName();
            Global.allLoadedClasses.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(outerClassName + "$"))
                    .flatMap(entry -> entry.getValue().stream())
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
        if (!result.isSuccess()) {
            Global.deleteTransformer(transformer.getUuid());
        }
        return result;
    }
}

