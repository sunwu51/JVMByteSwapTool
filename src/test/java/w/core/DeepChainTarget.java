package w.core;

/**
 * A class with a long chain of nested anonymous classes plus many lambdas,
 * used to reproduce decompile retry-limit behaviour.
 */
public class DeepChainTarget {

    public String go() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Runnable r2 = new Runnable() {
                    @Override
                    public void run() {
                        Runnable r3 = new Runnable() {
                            @Override
                            public void run() {
                                Runnable r4 = new Runnable() {
                                    @Override
                                    public void run() {
                                        Runnable r5 = new Runnable() {
                                            @Override
                                            public void run() {
                                                Runnable r6 = new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        Runnable r7 = new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                Runnable r8 = new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        Runnable r9 = new Runnable() {
                                                                            @Override
                                                                            public void run() {
                                                                                hello();
                                                                            }
                                                                        };
                                                                        r9.run();
                                                                    }
                                                                };
                                                                r8.run();
                                                            }
                                                        };
                                                        r7.run();
                                                    }
                                                };
                                                r6.run();
                                            }
                                        };
                                        r5.run();
                                    }
                                };
                                r4.run();
                            }
                        };
                        r3.run();
                    }
                };
                r2.run();
            }
        };
        r.run();
        return "go";
    }

    public String manyLambdas() {
        Runnable r0 = () -> System.out.println("0");
        Runnable r1 = () -> System.out.println("1");
        Runnable r2 = () -> System.out.println("2");
        Runnable r3 = () -> System.out.println("3");
        Runnable r4 = () -> System.out.println("4");
        Runnable r5 = () -> System.out.println("5");
        Runnable r6 = () -> System.out.println("6");
        Runnable r7 = () -> System.out.println("7");
        Runnable r8 = () -> System.out.println("8");
        Runnable r9 = () -> System.out.println("9");
        Runnable r10 = () -> System.out.println("10");
        Runnable r11 = () -> System.out.println("11");
        Runnable r12 = () -> System.out.println("12");
        r0.run();
        r1.run();
        r2.run();
        r3.run();
        r4.run();
        r5.run();
        r6.run();
        r7.run();
        r8.run();
        r9.run();
        r10.run();
        r11.run();
        r12.run();
        return "manyLambdas";
    }

    private String hello() {
        return "hi";
    }
}
