package w.core;

import w.*;
import w.core.model.*;
import w.web.message.*;

import java.lang.reflect.Modifier;
import java.util.*;


public class Swapper {
    private static final Swapper INSTANCE = new Swapper();

    private Swapper() {}

    public static Swapper getInstance() {
        return INSTANCE;
    }

    public boolean swap(Message message) {
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
                default:
                    Global.error("type not support");
                    throw new RuntimeException("message type not support");
            }
        } catch (Throwable e) {
            Global.error("build transform error:", e);
            return false;
        }

        Set<Class<?>> classes = Global.allLoadedClasses.getOrDefault(transformer.getClassName(), new HashSet<>());

        boolean exists = false;
        for (Class<?> aClass : classes) {
            if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                Set<String> candidates = new HashSet<>();
                for (Object instances : Global.getInstances(aClass)) {
                    candidates.add(instances.getClass().getName());
                }
                Global.error("!Error: Should use a simple pojo, but " + aClass.getName() + " is a Interface or Abstract class or something wired, \nmaybe you should use: " + candidates);
                return false;
            }
            exists = true;
        }

        if (!exists) {
            Global.error("Class not exist" + transformer.getClassName());
            return false;
        }

        Global.addTransformer(transformer);
        Global.debug("add transformer finish, will retrans class");


        for (Class<?> aClass : classes) {
            try {
                Global.addActiveTransformer(aClass, transformer);
            } catch (Throwable e) {
                Global.error("re transformer error:", e);
                return false;
            }
        }

        return true;
    }
}


