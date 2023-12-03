package w.core;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Emitter {
    private static final Emitter INSTANCE = new Emitter();
    private Emitter(){}

    public static Emitter getInstance() {
        return INSTANCE;
    }


}
