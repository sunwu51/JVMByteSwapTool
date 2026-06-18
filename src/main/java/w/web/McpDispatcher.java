package w.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import w.Global;
import w.core.ExecBundle;
import w.core.GroovyBundle;
import w.core.Swapper;
import w.core.model.SwapResult;
import w.util.RequestUtils;
import w.web.message.ChangeBodyMessage;
import w.web.message.ChangeResultMessage;
import w.web.message.DecompileMessage;
import w.web.message.DeleteMessage;
import w.web.message.EvalMessage;
import w.web.message.ExecMessage;
import w.web.message.Message;
import w.web.message.OuterWatchMessage;
import w.web.message.ReplaceClassMessage;
import w.web.message.TraceMessage;
import w.web.message.WatchMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class McpDispatcher {
    private static final String JSONRPC = "2.0";

    private final Swapper swapper = Swapper.getInstance();

    public Map<String, Object> dispatch(String body) throws Exception {
        JSONObject request = JSON.parseObject(body);
        Object id = request.get("id");
        String method = request.getString("method");
        if (method == null || method.isEmpty()) {
            return error(id, -32600, "method is required");
        }

        if ("initialize".equals(method)) {
            return success(id, initializeResult());
        }
        if ("tools/list".equals(method)) {
            return success(id, toolsListResult());
        }
        if ("tools/call".equals(method)) {
            return success(id, callTool(request.getJSONObject("params"), id));
        }
        if ("ping".equals(method)) {
            return success(id, new HashMap<>());
        }
        if (method.startsWith("notifications/")) {
            return success(id, new HashMap<>());
        }

        return error(id, -32601, "method not found: " + method);
    }

    public static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = baseResponse(id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new LinkedHashMap<>());
        result.put("capabilities", capabilities);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "JVMByteSwapTool");
        serverInfo.put("version", "0.0.1");
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> toolsListResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("watch", "Attach a diagnostic watch transformer to a method in the host JVM. Use this to observe method calls in the already-running Java process without restarting it. Arguments identify the target class, method, parameter types, and watch expression/options. The synchronous result reports whether transformer installation and class retransformation succeeded, including per class/classloader apply results. Runtime method-hit output is asynchronous and is delivered through the /log event stream, not as this call's return value."));
        tools.add(tool("outer_watch", "Attach a diagnostic transformer in the host JVM to observe a caller/outer method relationship. Use this when debugging how a target method is reached from surrounding application code. Arguments identify the target class/method and matching options. The synchronous result reports transformer installation and retransformation status; later hit logs are streamed through /log."));
        tools.add(tool("trace", "Attach a trace transformer to a method in the host JVM. Use this for runtime call tracing and lightweight execution diagnostics inside the attached Java process. Arguments identify the class, method, parameter types, and trace options. The call returns only installation/retransform success or failure. Trace hits and runtime observations are pushed asynchronously to /log."));
        tools.add(tool("change_body", "Hot-swap a method body in the host JVM by installing a bytecode transformer and retransformation. Use only when intentionally changing live behavior for diagnosis or emergency patching. Arguments identify the class/method/signature and replacement body definition. The synchronous result is a SwapResult that reports transformer UUID and per class/classloader retransformation success or failure."));
        tools.add(tool("change_result", "Hot-swap the result of an inner method call or selected method execution in the host JVM. Use this to alter runtime return values for controlled diagnosis. Arguments identify the target class/method/signature and replacement result logic. The synchronous response reports transformer installation and retransformation outcome; later runtime effects are logged through /log if the transformer emits logs."));
        tools.add(tool("replace_class", "Replace a loaded class in the host JVM from base64-encoded bytecode. Use this for live class replacement when source has already been compiled externally. Arguments include the target class name and base64 class bytes. The result reports whether the class transformer was installed and whether retransformation succeeded for each affected loaded class/classloader."));
        tools.add(tool("decompile", "Decompile a class currently loaded in the host JVM. Use this to inspect the actual bytecode/source shape of a class from the running process, including classes loaded by different classloaders. Arguments identify the loaded class to decompile. The synchronous result reports transformer/retransform status; generated decompile output is emitted through the normal logging/output path."));
        tools.add(tool("exec", "Compile, hot-swap, and invoke the built-in w.Exec class inside the host JVM. Use this for imperative diagnostic code that can call agent APIs such as Global.info, SpringUtils, or OGNL helpers. This tool is effectively void: the synchronous result is only success/failure such as 'exec success'. Any diagnostic output from the executed code should be written with Global.info/Global.error and consumed from /log."));
        tools.add(tool("eval", "Evaluate a Groovy expression in the host JVM using the target application's classloader context. The variable 'ctx' is bound to the detected Spring ApplicationContext when available. Prefix the body with '!' to run a shell command instead of Groovy. The synchronous result returns a safe string representation of the expression result; complex host objects are not serialized as JSON. The same result is also logged to /log."));
        tools.add(tool("read_logs", "Read recent JVMByteSwapTool logs captured inside the host JVM. Use this after installing asynchronous diagnostics such as watch, outer_watch, trace, exec, reset, or decompile to retrieve runtime output that was not part of the original tool response. The required logId argument is the same logId you passed in the previous diagnostic tool's arguments, for example the logId supplied to watch. Logs are filtered by this logId to avoid returning unrelated host JVM noise. Pass logId='*' only when you intentionally want all recent logs. When timeoutMs is greater than 0, this call waits briefly for matching future logs, which lets an agent call watch and then wait for the method-hit logs produced when the application later handles traffic."));
        tools.add(tool("delete_transformer", "Remove one installed transformer from the host JVM by UUID and retransform affected classes to roll back that transformer. Use list_transformers first when the UUID is unknown. The synchronous result reports delete success or the failure reason."));
        tools.add(tool("reset", "Remove all installed JVMByteSwapTool transformers from the host JVM and retransform affected classes to roll back instrumentation. This is a broad cleanup operation. The synchronous result reports reset success/failure, and cleanup progress/errors are also written to /log."));
        tools.add(tool("list_transformers", "Return a snapshot of active transformers currently installed in the host JVM. The result is grouped by class name and classloader identity, because the same class name may be loaded by multiple classloaders. Each entry includes trace id, transformer description, and UUID. Use this before delete_transformer or to understand current live instrumentation state."));
        result.put("tools", tools);
        return result;
    }

    private Map<String, Object> tool(String name, String description) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema(name));
        return tool;
    }

    private Map<String, Object> inputSchema(String toolName) {
        Map<String, Object> schema = objectSchema();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        switch (toolName) {
            case "watch":
                add(properties, "logId", stringProp("Optional correlation id for runtime logs emitted by this watch. Reuse this same value as read_logs.logId to fetch method-hit logs later."));
                add(properties, "signature", stringProp("Target method in the host JVM, formatted as fully.qualified.ClassName#methodName. Example: com.example.UserService#getUser."));
                add(properties, "minCost", integerProp("Only emit runtime watch logs when invocation cost is at least this many milliseconds. Use 0 to log every hit."));
                add(properties, "printFormat", integerProp("Runtime value rendering format. 1 means toString, 2 means JSON serialization."));
                required.add("signature");
                break;
            case "outer_watch":
                add(properties, "logId", stringProp("Optional correlation id for runtime logs emitted by this outer watch. Reuse this same value as read_logs.logId."));
                add(properties, "signature", stringProp("Outer/caller method in the host JVM, formatted as fully.qualified.ClassName#methodName."));
                add(properties, "innerSignature", stringProp("Inner/callee method to observe inside the outer method, formatted as fully.qualified.ClassName#methodName."));
                add(properties, "printFormat", integerProp("Runtime value rendering format. 1 means toString, 2 means JSON serialization."));
                required.add("signature");
                required.add("innerSignature");
                break;
            case "trace":
                add(properties, "logId", stringProp("Optional correlation id for runtime logs emitted by this trace. Reuse this same value as read_logs.logId to fetch trace-hit logs later."));
                add(properties, "signature", stringProp("Target method to trace in the host JVM, formatted as fully.qualified.ClassName#methodName."));
                add(properties, "minCost", integerProp("Only emit trace summaries when total invocation cost is at least this many milliseconds. Use 0 to include every invocation."));
                add(properties, "ignoreZero", booleanProp("When true, omit traced sub-method entries whose measured cost is 0ms."));
                required.add("signature");
                break;
            case "change_body":
                add(properties, "logId", stringProp("Optional correlation id for install/retransform logs emitted by this change. Reuse this same value as read_logs.logId when needed."));
                addChangeTargetProperties(properties);
                add(properties, "body", stringProp("Replacement Java method body. For Javassist mode this is a code block such as { return \"patched\"; }."));
                add(properties, "mode", integerProp("Replacement engine. 0 means Javassist body replacement, 1 means ASM-style method replacement where supported."));
                required.add("className");
                required.add("method");
                required.add("paramTypes");
                required.add("body");
                break;
            case "change_result":
                add(properties, "logId", stringProp("Optional correlation id for install/retransform logs emitted by this change. Reuse this same value as read_logs.logId when needed."));
                addChangeTargetProperties(properties);
                add(properties, "innerClassName", stringProp("Class name of the inner method call to intercept. Use * to match by innerMethod regardless of owner where supported."));
                add(properties, "innerMethod", stringProp("Name of the inner method call whose result should be replaced."));
                add(properties, "body", stringProp("Replacement Java code for the intercepted inner result. The original result is available as $_ and arguments are available as $1, $2, etc. where supported."));
                add(properties, "mode", integerProp("Replacement engine. 0 means Javassist, 1 means ASM-style replacement where supported."));
                required.add("className");
                required.add("method");
                required.add("paramTypes");
                required.add("innerClassName");
                required.add("innerMethod");
                required.add("body");
                break;
            case "replace_class":
                add(properties, "logId", stringProp("Optional correlation id for logs emitted while replacing the class. Reuse this same value as read_logs.logId when needed."));
                add(properties, "className", stringProp("Fully qualified class name in the host JVM to replace. Example: com.example.UserService."));
                add(properties, "content", stringProp("Base64-encoded .class file bytes to install into the already-loaded target class."));
                required.add("className");
                required.add("content");
                break;
            case "decompile":
                add(properties, "logId", stringProp("Optional correlation id for decompile output logs. Reuse this same value as read_logs.logId to fetch generated output."));
                add(properties, "className", stringProp("Fully qualified class name currently loaded in the host JVM to decompile. Example: com.example.UserService."));
                required.add("className");
                break;
            case "exec":
                add(properties, "logId", stringProp("Optional correlation id for logs emitted by Global.info/Global.error inside w.Exec. Reuse this same value as read_logs.logId."));
                add(properties, "body", stringProp("Full Java source for class w.Exec with a public void exec() method. Use Global.info/Global.error for output; output is streamed through /log."));
                add(properties, "mode", integerProp("Reserved execution mode. Current UI sends 1; the current backend treats exec as compile/swap/invoke of w.Exec."));
                required.add("body");
                break;
            case "eval":
                add(properties, "logId", stringProp("Optional correlation id for the eval result log. Reuse this same value as read_logs.logId."));
                add(properties, "body", stringProp("Groovy expression or statement to evaluate in the host JVM. The variable ctx is the Spring ApplicationContext when detected. Prefix with ! to run a shell command instead of Groovy."));
                required.add("body");
                break;
            case "read_logs":
                add(properties, "logId", stringProp("Required log correlation id. Use the same logId that was passed in the previous watch, trace, outer_watch, exec, eval, decompile, or swap tool arguments. Pass * only when you intentionally want all recent logs."));
                add(properties, "since", integerProp("Optional epoch milliseconds lower bound. Only logs whose timestamp is greater than or equal to this value are returned."));
                add(properties, "timeoutMs", integerProp("Optional wait time in milliseconds when no matching logs are currently available. Defaults to 0 and is capped at 30000."));
                add(properties, "maxLines", integerProp("Maximum log entries to return. Defaults to 100 and is capped at 500."));
                required.add("logId");
                break;
            case "delete_transformer":
                add(properties, "logId", stringProp("Optional correlation id for delete logs. Reuse this same value as read_logs.logId when needed."));
                add(properties, "uuid", stringProp("UUID of the installed transformer to remove. Use list_transformers to discover UUIDs."));
                required.add("uuid");
                break;
            case "reset":
                add(properties, "logId", stringProp("Optional correlation id for reset logs. Reuse this same value as read_logs.logId to fetch cleanup output."));
                break;
            case "list_transformers":
                add(properties, "logId", stringProp("Optional correlation id for this listing request."));
                break;
            default:
                schema.put("additionalProperties", true);
                break;
        }

        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private void addChangeTargetProperties(Map<String, Object> properties) {
        add(properties, "className", stringProp("Fully qualified class name in the host JVM. Example: com.example.UserService."));
        add(properties, "method", stringProp("Target method name declared on className. Example: getUser."));
        add(properties, "paramTypes", arrayProp("Ordered Java parameter type names used to disambiguate overloaded methods. Use [] for no-arg methods. Examples: [\"java.lang.String\"], [\"int\", \"java.util.List\"]."));
    }

    private Map<String, Object> objectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private void add(Map<String, Object> properties, String name, Map<String, Object> property) {
        properties.put(name, property);
    }

    private Map<String, Object> stringProp(String description) {
        return prop("string", description);
    }

    private Map<String, Object> integerProp(String description) {
        return prop("integer", description);
    }

    private Map<String, Object> booleanProp(String description) {
        return prop("boolean", description);
    }

    private Map<String, Object> arrayProp(String description) {
        Map<String, Object> property = prop("array", description);
        property.put("items", stringProp("Java type name."));
        return property;
    }

    private Map<String, Object> prop(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private Map<String, Object> callTool(JSONObject params, Object id) throws Exception {
        if (params == null) {
            return toolResult(false, "params is required", null);
        }
        String name = normalizeToolName(params.getString("name"));
        JSONObject args = params.getJSONObject("arguments");
        if (args == null) {
            args = new JSONObject();
        }

        String traceId = resolveTraceId(args, id);
        RequestUtils.initRequestCtx(traceId);
        try {
            return doCallTool(name, args, traceId);
        } finally {
            RequestUtils.clearRequestCtx();
        }
    }

    String resolveTraceId(JSONObject args, Object id) {
        String logId = args.getString("logId");
        return logId == null || logId.trim().isEmpty()
                ? (id == null ? UUID.randomUUID().toString() : String.valueOf(id))
                : logId.trim();
    }

    private Map<String, Object> doCallTool(String name, JSONObject args, String traceId) throws Exception {
        switch (name) {
            case "watch":
                return swapResult(args.toJavaObject(WatchMessage.class), traceId);
            case "outer_watch":
                return swapResult(args.toJavaObject(OuterWatchMessage.class), traceId);
            case "trace":
                return swapResult(args.toJavaObject(TraceMessage.class), traceId);
            case "change_body":
                return swapResult(args.toJavaObject(ChangeBodyMessage.class), traceId);
            case "change_result":
                return swapResult(args.toJavaObject(ChangeResultMessage.class), traceId);
            case "replace_class":
                return swapResult(args.toJavaObject(ReplaceClassMessage.class), traceId);
            case "decompile":
                return swapResult(args.toJavaObject(DecompileMessage.class), traceId);
            case "exec":
                return exec(args.toJavaObject(ExecMessage.class), traceId);
            case "eval":
                return eval(args.toJavaObject(EvalMessage.class), traceId);
            case "read_logs":
                return readLogs(args);
            case "delete_transformer":
            case "delete":
                return delete(args.toJavaObject(DeleteMessage.class));
            case "reset":
                return reset();
            case "list_transformers":
            case "ping":
                return toolResult(true, "ok", listActiveTransformers());
            default:
                return toolResult(false, "unsupported tool: " + name, null);
        }
    }

    private Map<String, Object> swapResult(Message message, String traceId) {
        message.setId(traceId);
        SwapResult result = swapper.swap(message);
        return toolResult(result.isSuccess(), result.getMessage(), result);
    }

    private Map<String, Object> exec(ExecMessage message, String traceId) throws Exception {
        message.setId(traceId);
        ExecBundle.changeBodyAndInvoke(message.getBody());
        return toolResult(true, "exec success", null);
    }

    private Map<String, Object> eval(EvalMessage message, String traceId) {
        message.setId(traceId);
        try {
            Object res = GroovyBundle.eval(message.getBody());
            Global.info((message.getBody().startsWith("!") ?
                    "$ " + message.getBody().substring(1) : "groovy > " + message.getBody()) + "\n> " + res);
            return toolResult(true, "eval success", stringifyEvalResult(res));
        } catch (Exception e) {
            Global.error(e.toString(), e);
            return toolResult(false, e.getMessage(), e.toString());
        }
    }

    private String stringifyEvalResult(Object result) {
        if (result == null) {
            return null;
        }
        try {
            return String.valueOf(result);
        } catch (Throwable e) {
            return result.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(result));
        }
    }

    private Map<String, Object> delete(DeleteMessage message) {
        if (message.getUuid() == null) {
            return toolResult(false, "uuid is required", null);
        }
        try {
            Global.deleteTransformer(UUID.fromString(message.getUuid()));
            return toolResult(true, "delete success", message.getUuid());
        } catch (Exception e) {
            Global.error("delete error:", e);
            return toolResult(false, e.getMessage(), e.toString());
        }
    }

    private Map<String, Object> readLogs(JSONObject args) {
        String logId = args.getString("logId");
        if (logId == null || logId.trim().isEmpty()) {
            return toolResult(false, "logId is required; pass * only when you intentionally want all recent logs", null);
        }
        logId = logId.trim();
        long since = getLong(args, "since", 0);
        long timeoutMs = getLong(args, "timeoutMs", 0);
        int maxLines = getInt(args, "maxLines", 100);
        List<Map<String, Object>> logs = Global.readLogs(logId, since, maxLines, timeoutMs);
        return toolResult(true, logs.isEmpty() ? "no logs" : "logs found", logs);
    }

    private long getLong(JSONObject args, String name, long defaultValue) {
        try {
            Long value = args.getLong(name);
            return value == null ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int getInt(JSONObject args, String name, int defaultValue) {
        try {
            Integer value = args.getInteger(name);
            return value == null ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Map<String, Object> reset() {
        Global.reset();
        Global.info("reset finished");
        return toolResult(true, "reset success", null);
    }

    private Map<String, Map<String, List<String>>> listActiveTransformers() {
        Map<String, Map<String, List<String>>> content = new LinkedHashMap<>();
        synchronized (Global.class) {
            Global.activeTransformers.forEach((className, loaderToTransformers) -> {
                Map<String, List<String>> transformersByLoader = new LinkedHashMap<>();
                loaderToTransformers.forEach((loader, transformers) -> {
                    transformersByLoader.put(loader, transformers.stream()
                            .map(transformer -> transformer.getTraceId() + "_" + transformer.desc() + "_" + transformer.getUuid())
                            .collect(Collectors.toList()));
                });
                content.put(className, transformersByLoader);
            });
        }
        return content;
    }

    private Map<String, Object> toolResult(boolean success, String message, Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("message", message);
        payload.put("data", data);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("isError", !success);
        result.put("structuredContent", payload);

        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", safePayloadText(payload));
        content.add(text);
        result.put("content", content);
        return result;
    }

    private String safePayloadText(Map<String, Object> payload) {
        String json = Global.toJson(payload);
        if (!json.startsWith("toJson error: ")) {
            return json;
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("success", payload.get("success"));
        fallback.put("message", payload.get("message"));
        fallback.put("data", String.valueOf(payload.get("data")));
        return Global.toJson(fallback);
    }

    private String normalizeToolName(String name) {
        return name == null ? "" : name.trim().toLowerCase().replace('-', '_');
    }

    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = baseResponse(id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> baseResponse(Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSONRPC);
        response.put("id", id);
        return response;
    }
}
