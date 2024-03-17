package com.example;

import jexer.TAction;
import jexer.TApplication;
import jexer.TEditorWidget;
import jexer.TField;
import jexer.TWindow;
import jexer.event.TMenuEvent;
import jexer.menu.TMenu;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

/**
 * @author Frank
 * @date 2024/3/16 11:30
 */

public class Tui extends TApplication {
    TWindow twatch = genWatchWindow();

    TWindow tchange = genChangeBodyWindow();

    TWindow texec = genExecWindow();

    TWindow trep = genReplaceClassWindow();

    final TWindow tlog;

    final TEditorWidget logger;

    LinkedList<String> logQueue = new LinkedList<>();

    Map<Integer, TWindow> id2Win = new HashMap<>();

    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-mm-dd HH:MM:ss");

    Ws ws;

    public Tui(Ws ws) throws Exception {
        super(BackendType.XTERM);
        this.ws = ws;
        TMenu menu = this.addMenu("&Menu");
        menu.addItem(1001, "watch");
        menu.addItem(1002, "changeBody");
        menu.addItem(1003, "exec");
        menu.addItem(1004, "replace");
        menu.addItem(1005, "log");

        menu.addItem(9999, "exit");

        Object[] o = genLogWindow();

        tlog = (TWindow) o[0];
        logger = (TEditorWidget) o[1];
        this.tchange.hide();
        this.twatch.hide();
        this.trep.hide();
        id2Win.put(1001, twatch);
        id2Win.put(1002, tchange);
        id2Win.put(1003, texec);
        id2Win.put(1004, trep);
        id2Win.put(1005, tlog);

    }

    private Object[] genLogWindow() {
        // 创建一个新的窗口
        TWindow window = new TWindow(this, "Log", 100, 0, 140, 40) {
            @Override
            public void onClose() {
                this.hide();
            }
        };

        TEditorWidget logger = window.addEditor("", 1, 1, window.getWidth(), window.getHeight());
        return new Object[] { window, logger };
    }

    private TWindow genWatchWindow() {

        // 创建一个新的窗口
        TWindow window = new TWindow(this, "Watch", 0, 0, 100, 40, TWindow.HIDEONCLOSE);
        // 添加标签和输入框
        window.addLabel("method signature", 1, 2);
        TField methodInput = window.addField(18, 2, 77, false, "");

        window.addButton("&Watch", 18, 4, new TAction() {
            public void DO() {
                if (methodInput.getText().split("#").length != 2) {
                    showLog("client error, method signature error");
                    return;
                }
                SendEvent event = new SendEvent("WATCH");
                event.put("signature", methodInput.getText());
                handleSubmit(event);
            }
        });

        window.addButton("&Trace", 32, 4, new TAction() {
            public void DO() {
                if (methodInput.getText().split("#").length != 2) {
                    showLog("client error, method signature error");
                    return;
                }
                SendEvent event = new SendEvent("TRACE");
                event.put("signature", methodInput.getText());
                handleSubmit(event);
            }
        });

        window.addLabel("outer method", 1, 6);
        TField outerMethodInput = window.addField(18, 6, 77, false, "");
        window.addLabel("inner method", 1, 8);
        TField innerMethodInput = window.addField(18, 8, 77, false, "");
        window.addButton("&OuterWatch", 18, 10, new TAction() {
            public void DO() {
                if (outerMethodInput.getText().split("#").length != 2
                        || innerMethodInput.getText().split("#").length != 2) {
                    showLog("client error, method signature error");
                    return;
                }
                SendEvent event = new SendEvent("OUTER_WATCH");
                event.put("signature", outerMethodInput.getText());
                event.put("innerSignature", innerMethodInput.getText());
                handleSubmit(event);
            }
        });
        return window;
    }

    private TWindow genChangeBodyWindow() {
        // 创建一个新的窗口
        TWindow window = new TWindow(this, "ChangeBody", 0, 0, 100, 40, TWindow.HIDEONCLOSE);
        window.addLabel("method signature", 1, 2);
        TField methodInput = window.addField(18, 2, 77, false, "");

        window.addLabel("param list", 1, 4);
        TField paramInput = window.addField(18, 4, 77, false, "");
        TEditorWidget edit = window.addEditor("{\r\n" + //
                "\t// write some java code\r\n" + //
                "}", 18, 6, 77, 10);
        window.addButton("&ChangeBody", 18, 18, new TAction() {
            public void DO() {
                String[] arr = null;
                if ((arr = methodInput.getText().split("#")).length != 2) {
                    showLog("client error, method signature error");
                    return;
                }
                SendEvent event = new SendEvent("CHANGE_BODY");
                event.put("className", arr[0]);
                event.put("method", arr[1]);
                event.put("paramTypes", paramInput.getText());
                event.put("body", edit.getText());
                handleSubmit(event);
            }
        });

        window.addLabel("outer method", 1, 20);
        TField outer = window.addField(18, 20, 77, false, "");

        window.addLabel("outer param list", 1, 22);
        TField parm = window.addField(18, 22, 77, false, "");
        window.addLabel("inner methor", 1, 24);
        TField inner = window.addField(18, 24, 77, false, "");
        TEditorWidget edit2 = window.addEditor("// $_ = \"new return value\";", 18, 26, 77, 10);
        window.addButton("&ChangeResult", 18, 38, new TAction() {
            public void DO() {
                String[] arr = null, arr2 = null;
                if ((arr = outer.getText().split("#")).length != 2 || (arr2 = inner.getText().split("#")).length != 2) {
                    showLog("client error, method signature error");
                    return;
                }
                SendEvent event = new SendEvent("CHANGE_RESULT");
                event.put("className", arr[0]);
                event.put("method", arr[1]);
                event.put("paramTypes", parm.getText());
                event.put("innerClassName", arr2[0]);
                event.put("innerMethod", arr2[1]);

                event.put("body", edit2.getText());
                handleSubmit(event);
            }
        });

        return window;
    }

    private TWindow genExecWindow() {
        // 创建一个新的窗口
        TWindow window = new TWindow(this, "Exec", 0, 0, 100, 40, TWindow.HIDEONCLOSE);
        TEditorWidget edit = window.addEditor("{\r\n" + //
                "    try {\r\n" + //
                "       // write some java code\r\n" + //
                "       // w.Global.info(w.Global.ognl(\"#root\", ctx));\r\n" + //
                "    } catch(Exception e) {\r\n" + //
                "       w.Global.info(e.toString());\r\n" + //
                "    }\r\n" + //
                "}", 1, 1, 90, 20);
        window.addButton("&Exec", 18, 22, new TAction() {
            public void DO() {
                SendEvent event = new SendEvent("EXEC");
                event.put("body", edit.getText());
                handleSubmit(event);
            }
        });

        return window;
    }

    private TWindow genReplaceClassWindow() {
        TWindow window = new TWindow(this, "ReplaceClass", 0, 0, 100, 40, TWindow.HIDEONCLOSE);
        window.addLabel("file path", 1, 2);
        TField file = window.addField(18, 2, 77, false, "");

        window.addLabel("class name", 1, 4);
        TField cls = window.addField(18, 4, 77, false, "");

        window.addButton("&Submit", 18, 6, new TAction() {
            public void DO() {
                showLog("replace class not supported");
            }
        });
        return window;
    }

    private void handleSubmit(SendEvent event) {
        Map<String, Object> res = new HashMap<>();
        res.put("type", event.type);
        res.put("id", event.id);
        res.put("timestamp", event.timestamp);
        res.putAll(event.details);

        ws.send(res);
    }

    @Override
    protected boolean onMenu(TMenuEvent menu) {
        if (menu.getId() == 9999) {
            this.exit();
            new Thread(() -> {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                }
                ws.close();
                System.out.println("good bye");
                System.exit(0);
            }).start();
            return true;
        }
        if (menu.getId() >= 1005) {
            id2Win.get(menu.getId()).show();
            return true;
        }

        id2Win.forEach((k, v) -> {
            if (k < 1005) {
                if (k == menu.getId()) {
                    v.show();
                } else {
                    v.hide();
                }
            }
        });
        return true;
    }

    public void showLog(String log) {
        String now = dtf.format(LocalDateTime.now());
        log = now + " " + log;
        logQueue.addFirst(log);
        if (logQueue.size() > 100) {
            logQueue.removeLast();
        }
        String content = "";
        for (String l : logQueue) {
            content = content + l + "\r\n";
        }
        logger.setText(content);
    }
}

class SendEvent {
    String type;
    String id = UUID.randomUUID().toString().substring(0, 4);
    long timestamp = System.currentTimeMillis();
    Map<String, Object> details = new HashMap<>();

    public SendEvent(String type) {
        this.type = type;
    }

    public void put(String key, String value) {
        details.put(key, value);
    }
}

interface Ws {
    void send(Map<String, Object> map);
    void close();
}