package w.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import net.bytebuddy.agent.ByteBuddyAgent;
import w.Global;
import w.util.RequestUtils;

import java.util.HashSet;
import java.lang.instrument.Instrumentation;

public class McpDispatcherTest {
    private final McpDispatcher dispatcher = new McpDispatcher();

    @Test
    public void initializeShouldReturnToolsCapability() throws Exception {
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")));

        Assertions.assertEquals("2.0", response.getString("jsonrpc"));
        Assertions.assertEquals(1, response.getInteger("id"));
        Assertions.assertNotNull(response.getJSONObject("result").getJSONObject("capabilities").getJSONObject("tools"));
        Assertions.assertEquals("JVMByteSwapTool", response.getJSONObject("result").getJSONObject("serverInfo").getString("name"));
    }

    @Test
    public void toolsListShouldExposeMcpTools() throws Exception {
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":\"tools\",\"method\":\"tools/list\"}")));

        JSONArray tools = response.getJSONObject("result").getJSONArray("tools");
        Assertions.assertTrue(tools.stream().map(it -> ((JSONObject) it).getString("name")).anyMatch("watch"::equals));
        Assertions.assertTrue(tools.stream().map(it -> ((JSONObject) it).getString("name")).anyMatch("reset"::equals));
        Assertions.assertTrue(tools.stream().map(it -> ((JSONObject) it).getString("name")).anyMatch("list_transformers"::equals));
        Assertions.assertTrue(tools.stream().map(it -> ((JSONObject) it).getString("name")).anyMatch("find_subclasses"::equals));
        Assertions.assertTrue(tools.stream().map(it -> ((JSONObject) it).getString("name")).anyMatch("list_classloaders"::equals));
        for (String toolName : new String[]{"search_loaded_classes", "inspect_debug_class",
                "add_debug_breakpoint", "get_debug_breakpoint", "list_debug_breakpoints",
                "list_debug_hits", "get_debug_hit", "remove_debug_breakpoint",
                "clear_debug_history"}) {
            Assertions.assertNotNull(findTool(tools, toolName), toolName);
        }
        Assertions.assertNull(findTool(tools, "list_debug_snapshots"));
        Assertions.assertNull(findTool(tools, "get_debug_snapshot"));
        JSONObject addDebugBreakpoint = findTool(tools, "add_debug_breakpoint");
        Assertions.assertTrue(addDebugBreakpoint.getJSONObject("inputSchema")
                .getJSONArray("required").contains("displayLine"));
        Assertions.assertNotNull(addDebugBreakpoint.getJSONObject("inputSchema")
                .getJSONObject("properties").getJSONObject("adjustToNearest"));
        Assertions.assertNull(addDebugBreakpoint.getJSONObject("inputSchema")
                .getJSONObject("properties").getJSONObject("condition"));
        Assertions.assertTrue(findTool(tools, "list_debug_hits")
                .getJSONObject("inputSchema").getJSONArray("required")
                .contains("breakpointId"));
        Assertions.assertTrue(findTool(tools, "get_debug_hit")
                .getJSONObject("inputSchema").getJSONArray("required")
                .contains("hitId"));
        JSONObject watch = findTool(tools, "watch");
        Assertions.assertNotNull(watch);
        Assertions.assertTrue(watch.getString("description").contains("ognl"));
        Assertions.assertTrue(watch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("ognl")
                .getString("description")
                .contains("root bound to the current this object"));
        Assertions.assertTrue(watch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("ognl")
                .getString("description")
                .contains("#req"));
        Assertions.assertNotNull(watch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("depthForJson"));
        Assertions.assertNotNull(watch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("variables"));
        Assertions.assertNotNull(watch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("classLoaderHash"));
        for (String toolName : new String[]{"outer_watch", "trace", "change_body",
                "change_result", "replace_class", "decompile"}) {
            Assertions.assertNotNull(findTool(tools, toolName).getJSONObject("inputSchema")
                    .getJSONObject("properties").getJSONObject("classLoaderHash"), toolName);
        }
        Assertions.assertTrue(watch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("ognl")
                .getString("description")
                .contains("@w.util.SpringUtils@getSpringBootApplicationContext().getBean(\"userController\").getUserById(1)"));

        JSONObject readLogs = findTool(tools, "read_logs");
        Assertions.assertNotNull(readLogs);
        Assertions.assertNotNull(readLogs.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("logId"));
        Assertions.assertNull(readLogs.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("id"));
        Assertions.assertTrue(readLogs.getJSONObject("inputSchema").getJSONArray("required").contains("logId"));

        JSONObject outerWatch = findTool(tools, "outer_watch");
        Assertions.assertNotNull(outerWatch);
        Assertions.assertTrue(outerWatch.getString("description").contains("*#methodName"));
        Assertions.assertTrue(outerWatch.getString("description").contains("ognl"));
        Assertions.assertTrue(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("innerSignature")
                .getString("description")
                .contains("*#methodName"));
        Assertions.assertTrue(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("ognl")
                .getString("description")
                .contains("root bound to the current this object"));
        Assertions.assertTrue(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("ognl")
                .getString("description")
                .contains("#req"));
        Assertions.assertNotNull(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("depthForJson"));
        Assertions.assertNotNull(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("variables"));
        Assertions.assertTrue(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("ognl")
                .getString("description")
                .contains("@w.util.SpringUtils@getSpringBootApplicationContext().getBean(\"userController\").getUserById(1)"));

        JSONObject changeResult = findTool(tools, "change_result");
        Assertions.assertNotNull(changeResult);
        Assertions.assertTrue(changeResult.getString("description").contains("$proceed()"));
        Assertions.assertTrue(changeResult.getString("description").contains("$_ = xxx"));
        Assertions.assertTrue(changeResult.getString("description")
                .contains("w.bridge.DiagnosticBridge.info"));
        Assertions.assertTrue(changeResult.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("body")
                .getString("description")
                .contains("$1 = newValue; $_ = $proceed();"));
        Assertions.assertTrue(changeResult.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("body")
                .getString("description")
                .contains("w.bridge.DiagnosticBridge.info"));

        JSONObject eval = findTool(tools, "eval");
        Assertions.assertNotNull(eval);
        Assertions.assertTrue(eval.getString("description").contains("ctx.getBean"));
        Assertions.assertTrue(eval.getString("description").contains("interactive binding"));
        Assertions.assertTrue(eval.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("body")
                .getString("description")
                .contains("variables assigned in one eval call remain available"));

        JSONObject findSubclasses = findTool(tools, "find_subclasses");
        Assertions.assertNotNull(findSubclasses);
        Assertions.assertNotNull(findSubclasses.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("className"));
        Assertions.assertTrue(findSubclasses.getJSONObject("inputSchema").getJSONArray("required").contains("className"));

        JSONObject listClassloaders = findTool(tools, "list_classloaders");
        Assertions.assertNotNull(listClassloaders);
        Assertions.assertFalse(listClassloaders.getJSONObject("inputSchema")
                .getJSONArray("required").contains("className"));
    }

    @Test
    public void unsupportedToolShouldReturnToolErrorPayload() throws Exception {
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":\"bad\",\"method\":\"tools/call\",\"params\":{\"name\":\"missing\",\"arguments\":{}}}")));

        JSONObject result = response.getJSONObject("result");
        Assertions.assertTrue(result.getBoolean("isError"));
        Assertions.assertFalse(result.getJSONObject("structuredContent").getBoolean("success"));
        Assertions.assertEquals("unsupported tool: missing", result.getJSONObject("structuredContent").getString("message"));
    }

    @Test
    public void clearDebugHistoryShouldReturnRemovalCounts() throws Exception {
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"id\":\"clear-debug\",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"clear_debug_history\",\"arguments\":{}}}")));

        JSONObject content = response.getJSONObject("result")
                .getJSONObject("structuredContent");
        Assertions.assertTrue(content.getBoolean("success"));
        Assertions.assertNotNull(content.getJSONObject("data").getInteger("removedCount"));
        Assertions.assertNotNull(
                content.getJSONObject("data").getInteger("retainedAttachedCount"));
    }

    @Test
    public void readLogsShouldReturnMatchingTraceLogs() throws Exception {
        String traceId = "read-log-test-" + System.nanoTime();
        long since = System.currentTimeMillis() - 1;
        RequestUtils.initRequestCtx(traceId);
        try {
            Global.info("read logs test message");
        } finally {
            RequestUtils.clearRequestCtx();
        }

        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"reader\",\"method\":\"tools/call\",\"params\":{\"name\":\"read_logs\",\"arguments\":{\"logId\":\""
                + traceId + "\",\"since\":" + since + ",\"maxLines\":10}}}";
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch(request)));

        JSONObject structuredContent = response.getJSONObject("result").getJSONObject("structuredContent");
        Assertions.assertTrue(structuredContent.getBoolean("success"));
        JSONArray logs = structuredContent.getJSONArray("data");
        Assertions.assertFalse(logs.isEmpty());
        JSONObject log = logs.getJSONObject(0);
        Assertions.assertEquals(traceId, log.getString("id"));
        Assertions.assertEquals("read logs test message", log.getString("content"));
    }

    @Test
    public void readLogsShouldRequireId() throws Exception {
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":\"reader\",\"method\":\"tools/call\",\"params\":{\"name\":\"read_logs\",\"arguments\":{}}}")));

        JSONObject structuredContent = response.getJSONObject("result").getJSONObject("structuredContent");
        Assertions.assertFalse(structuredContent.getBoolean("success"));
        Assertions.assertTrue(structuredContent.getString("message").contains("logId is required"));
    }

    @Test
    public void toolArgumentLogIdShouldBecomeLogTraceId() throws Exception {
        String traceId = "argument-id-test-" + System.nanoTime();
        JSONObject args = new JSONObject();
        args.put("logId", "  " + traceId + "  ");

        Assertions.assertEquals(traceId, dispatcher.resolveTraceId(args, "json-rpc-envelope-id"));
    }

    @Test
    public void findSubclassesShouldReturnLoadedAssignableClasses() throws Exception {
        Global.allLoadedClasses.computeIfAbsent(TestParent.class.getName(), it -> new HashSet<>()).add(TestParent.class);
        Global.allLoadedClasses.computeIfAbsent(TestChild.class.getName(), it -> new HashSet<>()).add(TestChild.class);
        try {
            long since = System.currentTimeMillis() - 1;
            String request = "{\"jsonrpc\":\"2.0\",\"id\":\"find-subclasses\",\"method\":\"tools/call\",\"params\":{\"name\":\"find_subclasses\",\"arguments\":{\"className\":\""
                    + TestParent.class.getName() + "\"}}}";
            JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch(request)));

            JSONObject structuredContent = response.getJSONObject("result").getJSONObject("structuredContent");
            Assertions.assertTrue(structuredContent.getBoolean("success"));
            JSONArray data = structuredContent.getJSONArray("data");
            Assertions.assertTrue(data.stream()
                    .map(it -> (JSONObject) it)
                    .anyMatch(it -> TestChild.class.getName().equals(it.getString("className"))
                            && it.getString("classLoader") != null));

            String readLogsRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"reader\",\"method\":\"tools/call\",\"params\":{\"name\":\"read_logs\",\"arguments\":{\"logId\":\"find-subclasses\",\"since\":"
                    + since + ",\"maxLines\":10}}}";
            JSONObject readLogsResponse = JSON.parseObject(globalJson(dispatcher.dispatch(readLogsRequest)));
            JSONArray logs = readLogsResponse.getJSONObject("result").getJSONObject("structuredContent").getJSONArray("data");
            Assertions.assertTrue(logs.stream()
                    .map(it -> ((JSONObject) it).getString("content"))
                    .anyMatch(content -> content.contains("find_subclasses " + TestParent.class.getName())
                            && content.contains(TestChild.class.getName())));
        } finally {
            Global.allLoadedClasses.getOrDefault(TestParent.class.getName(), new HashSet<>()).remove(TestParent.class);
            Global.allLoadedClasses.getOrDefault(TestChild.class.getName(), new HashSet<>()).remove(TestChild.class);
        }
    }

    @Test
    public void listClassloadersShouldReturnStructuredLoaderDetails() throws Exception {
        Instrumentation previous = Global.instrumentation;
        Global.instrumentation = ByteBuddyAgent.install();
        try {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":\"loaders\",\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"list_classloaders\",\"arguments\":"
                    + "{\"className\":\"java.lang.String\"}}}";
            JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch(request)));
            JSONObject structuredContent =
                    response.getJSONObject("result").getJSONObject("structuredContent");
            Assertions.assertTrue(structuredContent.getBoolean("success"));
            JSONObject target = structuredContent.getJSONArray("data").getJSONObject(0);
            Assertions.assertEquals("java.lang.String", target.getString("className"));
            Assertions.assertEquals("0", target.getString("classLoaderHash"));
            Assertions.assertEquals("bootstrap", target.getString("classLoaderName"));
            Assertions.assertNotNull(target.get("modifiable"));
            Assertions.assertTrue(target.containsKey("parentClassLoader"));
            Assertions.assertTrue(target.containsKey("codeSource"));
        } finally {
            Global.instrumentation = previous;
        }
    }

    @Test
    public void listClassloadersWithoutClassNameShouldEnumerateJvmLoaders() throws Exception {
        Instrumentation previous = Global.instrumentation;
        Global.instrumentation = ByteBuddyAgent.install();
        try {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":\"all-loaders\",\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"list_classloaders\",\"arguments\":{}}}";
            JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch(request)));
            JSONObject structuredContent =
                    response.getJSONObject("result").getJSONObject("structuredContent");
            Assertions.assertTrue(structuredContent.getBoolean("success"));
            Assertions.assertFalse(structuredContent.getJSONArray("data").isEmpty());
            JSONObject loader = structuredContent.getJSONArray("data").getJSONObject(0);
            Assertions.assertNotNull(loader.getString("classLoaderHash"));
            Assertions.assertNotNull(loader.getString("classLoaderName"));
            Assertions.assertTrue(loader.getInteger("classCount") > 0);
            Assertions.assertTrue(loader.getInteger("modifiableClassCount") >= 0);
        } finally {
            Global.instrumentation = previous;
        }
    }

    @Test
    public void readLogsShouldWaitForFutureMatchingLogs() throws Exception {
        String traceId = "read-log-wait-test-" + System.nanoTime();
        long since = System.currentTimeMillis();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
                RequestUtils.initRequestCtx(traceId);
                Global.info("future read logs test message");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                RequestUtils.clearRequestCtx();
            }
        });
        writer.start();

        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"reader\",\"method\":\"tools/call\",\"params\":{\"name\":\"read_logs\",\"arguments\":{\"logId\":\""
                + traceId + "\",\"since\":" + since + ",\"timeoutMs\":1000,\"maxLines\":10}}}";
        JSONObject response = JSON.parseObject(globalJson(dispatcher.dispatch(request)));
        writer.join();

        JSONArray logs = response.getJSONObject("result").getJSONObject("structuredContent").getJSONArray("data");
        Assertions.assertFalse(logs.isEmpty());
        Assertions.assertEquals("future read logs test message", logs.getJSONObject(0).getString("content"));
    }

    private JSONObject findTool(JSONArray tools, String name) {
        for (Object tool : tools) {
            JSONObject jsonObject = (JSONObject) tool;
            if (name.equals(jsonObject.getString("name"))) {
                return jsonObject;
            }
        }
        return null;
    }

    private String globalJson(Object obj) {
        return w.Global.toJson(obj);
    }

    private interface TestParent {
    }

    private static class TestChild implements TestParent {
    }
}
