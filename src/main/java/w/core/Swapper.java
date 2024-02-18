package w.core;

import w.*;
import w.core.model.*;
import w.web.message.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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
                default:
                    Global.error("type not support");
                    throw new RuntimeException("message type not support");
            }
        } catch (Throwable e) {
            Global.error("build transform error:", e);
            return false;
        }

        Global.addTransformer(transformer);
        Global.info("add transform finish, will retrans class");


        for (Class<?> aClass : Global.allLoadedClasses.getOrDefault(transformer.getClassName(), new HashSet<>())) {
            try {
                Global.addActiveTransformer(aClass, transformer);
            } catch (Throwable e) {
                Global.error("re transform error:", e);
                return false;
            }
        }

        return true;
    }
}


