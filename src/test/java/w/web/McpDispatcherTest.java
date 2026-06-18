package w.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import w.Global;
import w.util.RequestUtils;

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
        JSONObject readLogs = findTool(tools, "read_logs");
        Assertions.assertNotNull(readLogs);
        Assertions.assertNotNull(readLogs.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("logId"));
        Assertions.assertNull(readLogs.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("id"));
        Assertions.assertTrue(readLogs.getJSONObject("inputSchema").getJSONArray("required").contains("logId"));

        JSONObject outerWatch = findTool(tools, "outer_watch");
        Assertions.assertNotNull(outerWatch);
        Assertions.assertTrue(outerWatch.getString("description").contains("*#methodName"));
        Assertions.assertTrue(outerWatch.getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("innerSignature")
                .getString("description")
                .contains("*#methodName"));
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
}
