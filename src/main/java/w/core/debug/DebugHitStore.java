package w.core.debug;

import com.alibaba.fastjson2.JSON;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded asynchronous storage for Online Debug hit details.
 */
final class DebugHitStore {
    static final int DEFAULT_HIT_BYTES = 256 * 1024;
    static final long DEFAULT_TOTAL_BYTES = 32L * 1024L * 1024L;
    static final int DEFAULT_QUEUE_CAPACITY = 256;

    private static final int VALUE_DEPTH_LIMIT = 4;
    private static final int DETAIL_OVERHEAD_BYTES = 12 * 1024;
    private static final int MAX_CAPTURE_VALUES = 256;
    private static final int MAX_CAPTURE_FIELDS = 128;
    private static final int MAX_CAPTURE_STACK = 128;

    private final Object lock = new Object();
    private final LinkedHashMap<String, HitRecord> hits = new LinkedHashMap<>();
    private final Listener listener;
    private final int maxHitBytes;
    private final long maxTotalBytes;
    private final ThreadPoolExecutor captureExecutor;
    private long retainedBytes;

    DebugHitStore(Listener listener) {
        this(listener, DEFAULT_HIT_BYTES, DEFAULT_TOTAL_BYTES, DEFAULT_QUEUE_CAPACITY);
    }

    DebugHitStore(Listener listener, int maxHitBytes,
                  long maxTotalBytes, int queueCapacity) {
        this.listener = listener;
        this.maxHitBytes = maxHitBytes;
        this.maxTotalBytes = maxTotalBytes;
        captureExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "swapper-debug-capture");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    boolean submit(RawHit raw) {
        try {
            captureExecutor.execute(() -> process(raw));
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    boolean submit(CaptureRequest request) {
        try {
            captureExecutor.execute(() -> process(request));
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    boolean complete(Invocation invocation) {
        try {
            captureExecutor.execute(() -> completeNow(invocation));
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    void completeNow(Invocation invocation) {
        List<RawHit> updated = new ArrayList<>();
        synchronized (lock) {
            for (HitRecord record : hits.values()) {
                if (record.raw.invocation != invocation) {
                    continue;
                }
                retainedBytes -= record.bytes;
                applyReturn(record);
                record.bytes = detailBytes(record.detail);
                retainedBytes += record.bytes;
                updated.add(record.raw);
            }
        }
        for (RawHit raw : updated) {
            listener.onUpdated(raw);
        }
        evictOverBudget();
    }

    void storeCaptureError(RawHit raw, String error) {
        store(raw, errorDetail(raw, error), true);
    }

    List<Map<String, Object>> list(String breakpointId) {
        List<HitRecord> matching = new ArrayList<>();
        synchronized (lock) {
            for (HitRecord record : hits.values()) {
                if (record.raw.breakpointId.equals(breakpointId)) {
                    matching.add(record);
                }
            }
        }
        matching.sort(Comparator.comparingInt(
                (HitRecord record) -> record.raw.sequence).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < matching.size() && index < 100; index++) {
            result.add(summary(matching.get(index)));
        }
        return result;
    }

    Map<String, Object> get(String hitId) {
        HitRecord record;
        Map<String, Object> result;
        synchronized (lock) {
            record = hits.get(hitId);
            if (record == null) {
                throw new IllegalArgumentException("Debug hit not found or evicted: " + hitId);
            }
            if (!"PENDING".equals(returnStatus(record.raw.invocation))
                    || !"NOT_AT_RETURN".equals(breakpointReturnStatus(record.raw))) {
                retainedBytes -= record.bytes;
                applyReturn(record);
                record.bytes = detailBytes(record.detail);
                retainedBytes += record.bytes;
            }
            result = new LinkedHashMap<>(record.detail);
        }
        evictOverBudget();
        return result;
    }

    void removeBreakpoint(String breakpointId) {
        List<RawHit> removed = new ArrayList<>();
        synchronized (lock) {
            Iterator<Map.Entry<String, HitRecord>> iterator = hits.entrySet().iterator();
            while (iterator.hasNext()) {
                HitRecord record = iterator.next().getValue();
                if (!record.raw.breakpointId.equals(breakpointId)) {
                    continue;
                }
                retainedBytes -= record.bytes;
                removed.add(record.raw);
                iterator.remove();
            }
        }
        for (RawHit raw : removed) {
            raw.releaseCapturedValues();
            listener.onRemoved(raw);
        }
    }

    void clear() {
        synchronized (lock) {
            hits.clear();
            retainedBytes = 0;
        }
        captureExecutor.getQueue().clear();
    }

    int queueSize() {
        return captureExecutor.getQueue().size();
    }

    private void process(RawHit raw) {
        try {
            Map<String, Object> detail = serialize(raw);
            store(raw, detail, false);
        } catch (Throwable error) {
            store(raw, errorDetail(raw, describe(error)), true);
        }
    }

    private void process(CaptureRequest request) {
        try {
            process(request.capture());
        } catch (Throwable error) {
            storeCaptureError(request.failure(), "Capture failed: " + describe(error));
        }
    }

    private void store(RawHit raw, Map<String, Object> detail, boolean captureError) {
        HitRecord record = new HitRecord(raw, detail, detailBytes(detail));
        synchronized (lock) {
            if (!listener.accepts(raw.breakpointId)) {
                return;
            }
            HitRecord previous = hits.put(raw.hitId, record);
            if (previous != null) {
                retainedBytes -= previous.bytes;
            }
            retainedBytes += record.bytes;
            List<HitRecord> evicted = evictLocked();
            // Keep counter callbacks ordered with removal/eviction. Otherwise a
            // concurrent clear can remove this hit before onStored increments its
            // retained counter, leaving lifecycle totals inconsistent.
            listener.onStored(raw, captureError);
            raw.releaseCapturedValues();
            for (HitRecord removed : evicted) {
                removed.raw.releaseCapturedValues();
                listener.onEvicted(removed.raw);
            }
        }
    }

    private void evictOverBudget() {
        synchronized (lock) {
            List<HitRecord> evicted = evictLocked();
            for (HitRecord removed : evicted) {
                removed.raw.releaseCapturedValues();
                listener.onEvicted(removed.raw);
            }
        }
    }

    private List<HitRecord> evictLocked() {
        List<HitRecord> evicted = new ArrayList<>();
        Iterator<Map.Entry<String, HitRecord>> iterator = hits.entrySet().iterator();
        while (retainedBytes > maxTotalBytes && iterator.hasNext()) {
            HitRecord record = iterator.next().getValue();
            retainedBytes -= record.bytes;
            evicted.add(record);
            iterator.remove();
        }
        return evicted;
    }

    private Map<String, Object> serialize(RawHit raw) {
        int valueBudget = Math.max(1024, maxHitBytes - DETAIL_OVERHEAD_BYTES);
        ValueRenderer renderer = new ValueRenderer(valueBudget);
        ValueRenderer stackRenderer = new ValueRenderer(valueBudget);
        Map<String, Object> detail = baseDetail(raw);
        detail.put("parameters", renderValues(raw.parameters, renderer));
        detail.put("methodParameters", renderValues(raw.methodParameters, renderer));
        detail.put("instanceFields", renderValues(raw.instanceFields, renderer));
        detail.put("staticFields", renderValues(raw.staticFields, renderer));
        detail.put("callStack", renderStack(raw.stack, stackRenderer));
        detail.put("return", renderReturn(raw.invocation, renderer));
        detail.put("breakpointReturn", renderBreakpointReturn(raw, renderer));
        detail.put("captureError", null);
        detail.put("truncated", renderer.truncated);
        detail.put("asynchronousShallowSnapshot", true);
        if (detailBytes(detail) > maxHitBytes) {
            detail.put("parameters", truncateValues(raw.parameters));
            detail.put("methodParameters", truncateValues(raw.methodParameters));
            detail.put("instanceFields", truncateValues(raw.instanceFields));
            detail.put("staticFields", truncateValues(raw.staticFields));
            detail.put("return", truncatedReturn(raw.invocation));
            detail.put("breakpointReturn", truncatedBreakpointReturn(raw));
            detail.put("truncated", true);
            if (detailBytes(detail) > maxHitBytes) {
                detail.put("parameters", Collections.emptyList());
                detail.put("methodParameters", Collections.emptyList());
                detail.put("instanceFields", Collections.emptyList());
                detail.put("staticFields", Collections.emptyList());
                detail.put("truncationReason", "Hit metadata exceeded detail limit");
                trimStackToBudget(detail);
            }
        }
        return detail;
    }

    private void trimStackToBudget(Map<String, Object> detail) {
        Object value = detail.get("callStack");
        if (!(value instanceof List<?>)) {
            return;
        }
        List<?> stack = (List<?>) value;
        while (!stack.isEmpty() && detailBytes(detail) > maxHitBytes) {
            stack.remove(stack.size() - 1);
        }
    }

    private void applyReturn(HitRecord record) {
        ValueRenderer renderer = new ValueRenderer(Math.max(
                1024, maxHitBytes - Math.min(record.bytes, maxHitBytes - 1024)));
        record.detail.put("return", renderReturn(record.raw.invocation, renderer));
        if (!"SNAPSHOT".equals(breakpointReturnStatus(record.raw))) {
            record.detail.put("breakpointReturn", renderBreakpointReturn(record.raw, renderer));
        }
        if (renderer.truncated) {
            record.detail.put("truncated", true);
        }
        if (detailBytes(record.detail) > maxHitBytes) {
            record.detail.put("return", truncatedReturn(record.raw.invocation));
            record.detail.put("breakpointReturn", truncatedBreakpointReturn(record.raw));
            record.detail.put("truncated", true);
        }
    }

    private Map<String, Object> baseDetail(RawHit raw) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("hitId", raw.hitId);
        detail.put("breakpointId", raw.breakpointId);
        detail.put("sequence", raw.sequence);
        detail.put("capturedAt", raw.capturedAt);
        Map<String, Object> thread = new LinkedHashMap<>();
        thread.put("id", raw.threadId);
        thread.put("name", raw.threadName);
        thread.put("state", raw.threadState);
        thread.put("daemon", raw.threadDaemon);
        thread.put("priority", raw.threadPriority);
        detail.put("thread", thread);
        return detail;
    }

    private Map<String, Object> errorDetail(RawHit raw, String error) {
        Map<String, Object> detail = baseDetail(raw);
        detail.put("parameters", Collections.emptyList());
        detail.put("methodParameters", Collections.emptyList());
        detail.put("instanceFields", Collections.emptyList());
        detail.put("staticFields", Collections.emptyList());
        ValueRenderer renderer = new ValueRenderer(
                Math.max(1024, maxHitBytes - DETAIL_OVERHEAD_BYTES));
        detail.put("callStack", renderStack(raw.stack, renderer));
        detail.put("return", pendingReturn());
        detail.put("breakpointReturn", renderBreakpointReturn(raw, renderer));
        detail.put("captureError", error);
        detail.put("truncated", false);
        detail.put("asynchronousShallowSnapshot", true);
        return detail;
    }

    private List<Map<String, Object>> renderValues(
            List<CapturedValue> values, ValueRenderer renderer) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CapturedValue captured : values) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", captured.name);
            if (captured.declaringClass != null) {
                item.put("declaringClass", captured.declaringClass);
            }
            item.put("declaredType", captured.declaredType);
            if (captured.error == null) {
                item.put("value", renderer.render(captured.value, 0));
                item.put("error", null);
            } else {
                item.put("value", null);
                item.put("error", captured.error);
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> truncateValues(List<CapturedValue> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CapturedValue captured : values) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", captured.name);
            if (captured.declaringClass != null) {
                item.put("declaringClass", captured.declaringClass);
            }
            item.put("declaredType", captured.declaredType);
            item.put("value", truncatedValue(captured.value));
            item.put("error", captured.error);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> renderStack(
            StackTraceElement[] stack, ValueRenderer renderer) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean applicationFrameSeen = false;
        for (StackTraceElement frame : stack) {
            if (!applicationFrameSeen && isCaptureFrame(frame.getClassName())) {
                continue;
            }
            applicationFrameSeen = true;
            if (!renderer.reserveText(frame.getClassName(), 64)
                    || !renderer.reserveText(frame.getMethodName(), 24)
                    || !renderer.reserveText(frame.getFileName(), 24)) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", frame.getClassName());
            item.put("methodName", frame.getMethodName());
            item.put("fileName", frame.getFileName());
            item.put("lineNumber", frame.getLineNumber());
            item.put("nativeMethod", frame.isNativeMethod());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> renderReturn(
            Invocation invocation, ValueRenderer renderer) {
        return renderReturn(returnStatus(invocation), invocation.result,
                invocation.throwable, renderer);
    }

    private Map<String, Object> renderBreakpointReturn(
            RawHit raw, ValueRenderer renderer) {
        if ("SNAPSHOT".equals(breakpointReturnStatus(raw))) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SNAPSHOT");
            result.put("value", renderSnapshot(raw.localVariables, renderer));
            if (raw.localVariableMessage != null) {
                result.put("message", raw.localVariableMessage);
            }
            return result;
        }
        return renderReturn(breakpointReturnStatus(raw),
                breakpointResult(raw), breakpointThrowable(raw), renderer);
    }

    private Map<String, Object> renderReturn(
            String status, Object value, Throwable throwable, ValueRenderer renderer) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("status", status);
        if ("RETURNED".equals(status)) {
            rendered.put("value", renderer.render(value, 0));
        } else if ("THREW".equals(status)) {
            rendered.put("exception", renderer.render(throwable, 0));
        }
        return rendered;
    }

    private Map<String, Object> truncatedReturn(Invocation invocation) {
        return truncatedReturn(returnStatus(invocation), invocation.result,
                invocation.throwable);
    }

    private Map<String, Object> truncatedBreakpointReturn(RawHit raw) {
        if ("SNAPSHOT".equals(breakpointReturnStatus(raw))) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SNAPSHOT");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", "OBJECT");
            value.put("type", "breakpoint-line");
            value.put("fields", truncateValues(raw.localVariables));
            value.put("truncated", true);
            result.put("value", value);
            if (raw.localVariableMessage != null) {
                result.put("message", raw.localVariableMessage);
            }
            return result;
        }
        return truncatedReturn(breakpointReturnStatus(raw),
                breakpointResult(raw), breakpointThrowable(raw));
    }

    private Map<String, Object> renderSnapshot(
            List<CapturedValue> variables, ValueRenderer renderer) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "OBJECT");
        value.put("type", "breakpoint-line");
        value.put("fields", renderValues(variables, renderer));
        value.put("truncated", renderer.truncated);
        return value;
    }

    private Map<String, Object> truncatedReturn(
            String status, Object value, Throwable throwable) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        if ("RETURNED".equals(status)) {
            result.put("value", truncatedValue(value));
        } else if ("THREW".equals(status)) {
            result.put("exception", truncatedValue(throwable));
        }
        return result;
    }

    private Map<String, Object> pendingReturn() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PENDING");
        return result;
    }

    private Map<String, Object> summary(HitRecord record) {
        RawHit raw = record.raw;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hitId", raw.hitId);
        result.put("breakpointId", raw.breakpointId);
        result.put("sequence", raw.sequence);
        result.put("capturedAt", raw.capturedAt);
        result.put("threadId", raw.threadId);
        result.put("threadName", raw.threadName);
        result.put("returnStatus", returnStatus(raw.invocation));
        result.put("breakpointReturnStatus", breakpointReturnStatus(raw));
        result.put("truncated", Boolean.TRUE.equals(record.detail.get("truncated")));
        result.put("captureError", record.detail.get("captureError"));
        return result;
    }

    private static String returnStatus(Invocation invocation) {
        return invocation.status;
    }

    private static String breakpointReturnStatus(RawHit raw) {
        return raw.breakpointStatus == null
                ? raw.invocation.breakpointStatus : raw.breakpointStatus;
    }

    private static Object breakpointResult(RawHit raw) {
        return raw.breakpointStatus == null
                ? raw.invocation.breakpointResult : raw.breakpointResult;
    }

    private static Throwable breakpointThrowable(RawHit raw) {
        return raw.breakpointStatus == null
                ? raw.invocation.breakpointThrowable : raw.breakpointThrowable;
    }

    private static boolean isCaptureFrame(String className) {
        return "java.lang.Thread".equals(className)
                || className.startsWith("w.bridge.DiagnosticBridge")
                || className.startsWith("w.core.diagnostic.AgentDiagnosticListener")
                || className.startsWith("w.core.debug.DebugManager")
                || className.startsWith("w.core.debug.DebugHitStore");
    }

    private static int detailBytes(Map<String, Object> detail) {
        try {
            return JSON.toJSONBytes(detail).length;
        } catch (Throwable error) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, Object> truncatedValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "TRUNCATED");
        result.put("type", value == null ? null : value.getClass().getName());
        result.put("truncated", true);
        return result;
    }

    private static String describe(Throwable error) {
        String message;
        try {
            message = error.getMessage();
        } catch (Throwable ignored) {
            message = null;
        }
        return error.getClass().getName() + (message == null ? "" : ": " + message);
    }

    static RawHit capture(String hitId, String breakpointId, int sequence,
                          Class<?> targetClass, Object target, Object[] args,
                          List<String> parameterNames, List<String> parameterTypes,
                          String[] localNames, String[] localTypes, Object[] localValues,
                          String localVariableMessage,
                          Invocation invocation) {
        return capture(hitId, breakpointId, sequence, targetClass, target,
                args, parameterNames, parameterTypes,
                args, parameterNames, parameterTypes,
                localNames, localTypes, localValues, localVariableMessage,
                null, null, null, invocation, captureStack());
    }

    private static RawHit capture(String hitId, String breakpointId, int sequence,
                                  Class<?> targetClass, Object target, Object[] args,
                                  List<String> parameterNames, List<String> parameterTypes,
                                  String[] localNames, String[] localTypes, Object[] localValues,
                                  String localVariableMessage, Invocation invocation,
                                  StackTraceElement[] stack) {
        return capture(hitId, breakpointId, sequence, targetClass, target,
                args, parameterNames, parameterTypes,
                args, parameterNames, parameterTypes,
                localNames, localTypes, localValues, localVariableMessage,
                null, null, null, invocation, stack);
    }

    static RawHit captureCall(
            String hitId, String breakpointId, int sequence,
            Class<?> targetClass, Object target,
            Object[] methodArgs, List<String> methodParameterNames,
            List<String> methodParameterTypes,
            String[] callTypes, Object[] callArgs,
            String[] localNames, String[] localTypes, Object[] localValues,
            String localVariableMessage,
            Object result, Throwable throwable, boolean voidResult,
            boolean captureCallOutcome,
            Invocation invocation) {
        return captureCall(hitId, breakpointId, sequence, targetClass, target,
                methodArgs, methodParameterNames, methodParameterTypes,
                callTypes, callArgs, localNames, localTypes, localValues,
                localVariableMessage, result, throwable, voidResult,
                captureCallOutcome, invocation, captureStack());
    }

    private static RawHit captureCall(
            String hitId, String breakpointId, int sequence,
            Class<?> targetClass, Object target,
            Object[] methodArgs, List<String> methodParameterNames,
            List<String> methodParameterTypes,
            String[] callTypes, Object[] callArgs,
            String[] localNames, String[] localTypes, Object[] localValues,
            String localVariableMessage,
            Object result, Throwable throwable, boolean voidResult,
            boolean captureCallOutcome,
            Invocation invocation, StackTraceElement[] stack) {
        List<String> callParameterNames = new ArrayList<>();
        List<String> callParameterTypes = new ArrayList<>();
        int count = callArgs == null ? 0 : callArgs.length;
        boolean[] usedLocalNames = new boolean[localNames == null ? 0 : localNames.length];
        boolean[] usedMethodParameterNames = new boolean[
                methodParameterNames == null ? 0 : methodParameterNames.size()];
        for (int index = 0; index < count; index++) {
            callParameterNames.add(resolveCallParameterName(
                    index, callArgs[index], localNames, localValues, usedLocalNames,
                    methodArgs, methodParameterNames, usedMethodParameterNames));
            callParameterTypes.add(callTypes != null && index < callTypes.length
                    ? callTypes[index] : "java.lang.Object");
        }
        String status = !captureCallOutcome ? null : throwable != null ? "THREW"
                : voidResult ? "VOID" : "RETURNED";
        return capture(hitId, breakpointId, sequence, targetClass, target,
                callArgs, callParameterNames, callParameterTypes,
                methodArgs, methodParameterNames, methodParameterTypes,
                localNames, localTypes, localValues, localVariableMessage,
                status, result, throwable, invocation, stack);
    }

    private static RawHit capture(
            String hitId, String breakpointId, int sequence,
            Class<?> targetClass, Object target,
            Object[] args, List<String> parameterNames, List<String> parameterTypes,
            Object[] methodArgs, List<String> methodParameterNames,
            List<String> methodParameterTypes,
            String[] localNames, String[] localTypes, Object[] localValues,
            String localVariableMessage,
            String breakpointStatus, Object breakpointResult,
            Throwable breakpointThrowable, Invocation invocation,
            StackTraceElement[] stack) {
        Thread thread = Thread.currentThread();
        Object[] safeArgs = args == null ? new Object[0] : args;
        List<CapturedValue> parameters =
                captureParameters(safeArgs, parameterNames, parameterTypes);
        List<CapturedValue> methodParameters = captureParameters(
                methodArgs == null ? new Object[0] : methodArgs,
                methodParameterNames, methodParameterTypes);
        List<CapturedValue> localVariables = new ArrayList<>();
        if (localNames != null && localValues != null) {
            int count = Math.min(Math.min(localNames.length, localValues.length),
                    MAX_CAPTURE_VALUES);
            for (int index = 0; index < count; index++) {
                String name = localNames[index] == null || localNames[index].isEmpty()
                        ? "local" + index : localNames[index];
                String type = localTypes != null && index < localTypes.length
                        && localTypes[index] != null ? localTypes[index]
                        : localValues[index] == null ? "java.lang.Object"
                        : localValues[index].getClass().getName();
                localVariables.add(new CapturedValue(name, null, type,
                        localValues[index], null));
            }
        } else {
            if (target != null) {
                localVariables.add(new CapturedValue(
                        "this", targetClass.getName(), targetClass.getName(), target, null));
            }
            for (int index = 0; index < safeArgs.length; index++) {
                String name = index < parameterNames.size()
                        ? parameterNames.get(index) : parameterName(index);
                String type = index < parameterTypes.size()
                        ? parameterTypes.get(index) : safeArgs[index] == null
                        ? "java.lang.Object" : safeArgs[index].getClass().getName();
                localVariables.add(new CapturedValue(name, null, type,
                        safeArgs[index], null));
            }
        }
        List<CapturedValue> instanceFields =
                target == null ? Collections.emptyList() : captureFields(targetClass, target, false);
        List<CapturedValue> staticFields = captureFields(targetClass, null, true);
        return new RawHit(
                hitId, breakpointId, sequence, System.currentTimeMillis(),
                thread.getId(), thread.getName(), thread.getState().name(),
                thread.isDaemon(), thread.getPriority(), stack,
                parameters, methodParameters, localVariables, instanceFields, staticFields,
                localVariableMessage, breakpointStatus, breakpointResult,
                breakpointThrowable, invocation);
    }

    private static List<CapturedValue> captureParameters(
            Object[] args, List<String> names, List<String> types) {
        List<CapturedValue> parameters = new ArrayList<>();
        for (int index = 0; index < args.length && index < MAX_CAPTURE_VALUES; index++) {
            String name = index < names.size() ? names.get(index) : parameterName(index);
            String type = index < types.size() ? types.get(index)
                    : args[index] == null ? "java.lang.Object"
                    : args[index].getClass().getName();
            parameters.add(new CapturedValue(name, null, type, args[index], null));
        }
        return parameters;
    }

    private static String resolveCallParameterName(
            int index, Object value, String[] localNames, Object[] localValues,
            boolean[] usedLocalNames, Object[] methodArgs, List<String> methodParameterNames,
            boolean[] usedMethodParameterNames) {
        if (localNames != null && localValues != null) {
            int count = Math.min(localNames.length, localValues.length);
            for (int candidate = 0; candidate < count; candidate++) {
                if (usedLocalNames[candidate] || localNames[candidate] == null
                        || !sameCapturedValue(value, localValues[candidate])) {
                    continue;
                }
                usedLocalNames[candidate] = true;
                return localNames[candidate];
            }
        }
        if (methodArgs != null && methodParameterNames != null) {
            int count = Math.min(methodArgs.length, methodParameterNames.size());
            for (int candidate = 0; candidate < count; candidate++) {
                if (usedMethodParameterNames[candidate]
                        || methodParameterNames.get(candidate) == null
                        || !sameCapturedValue(value, methodArgs[candidate])) {
                    continue;
                }
                usedMethodParameterNames[candidate] = true;
                return methodParameterNames.get(candidate);
            }
        }
        return parameterName(index);
    }

    private static String parameterName(int index) {
        return "parameter" + (index + 1);
    }

    private static boolean sameCapturedValue(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null
                || left.getClass() != right.getClass()) {
            return false;
        }
        return left instanceof String || left instanceof Number
                || left instanceof Boolean || left instanceof Character
                || left instanceof Enum;
    }

    private static List<CapturedValue> captureFields(
            Class<?> targetClass, Object target, boolean staticFields) {
        List<CapturedValue> result = new ArrayList<>();
        for (Class<?> current = targetClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable error) {
                result.add(new CapturedValue(
                        "<fields>", current.getName(), null, null,
                        error.getClass().getName()));
                continue;
            }
            for (Field field : fields) {
                if (result.size() >= MAX_CAPTURE_FIELDS) {
                    return result;
                }
                if (field.isSynthetic()
                        || Modifier.isStatic(field.getModifiers()) != staticFields) {
                    continue;
                }
                Object value = null;
                String error = null;
                try {
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    value = field.get(staticFields ? null : target);
                } catch (Throwable fieldError) {
                    error = fieldError.getClass().getName();
                }
                result.add(new CapturedValue(
                        field.getName(), current.getName(),
                        field.getType().getTypeName(), value, error));
            }
        }
        return result;
    }

    private static StackTraceElement[] captureStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        return stack.length <= MAX_CAPTURE_STACK
                ? stack : Arrays.copyOf(stack, MAX_CAPTURE_STACK);
    }

    interface Listener {
        boolean accepts(String breakpointId);

        void onStored(RawHit raw, boolean captureError);

        void onUpdated(RawHit raw);

        void onEvicted(RawHit raw);

        void onRemoved(RawHit raw);
    }

    static final class CaptureRequest {
        private final String hitId;
        private final String breakpointId;
        private final int sequence;
        private final Class<?> targetClass;
        private final Object target;
        private final Object[] args;
        private final List<String> parameterNames;
        private final List<String> parameterTypes;
        private final Object[] methodArgs;
        private final List<String> methodParameterNames;
        private final List<String> methodParameterTypes;
        private final String[] callTypes;
        private final Object[] callArgs;
        private final String[] localNames;
        private final String[] localTypes;
        private final Object[] localValues;
        private final String localVariableMessage;
        private final Object result;
        private final Throwable throwable;
        private final boolean voidResult;
        private final boolean captureCallOutcome;
        private final boolean callHit;
        private final Invocation invocation;
        private final StackTraceElement[] stack;

        private CaptureRequest(String hitId, String breakpointId, int sequence,
                               Class<?> targetClass, Object target, Object[] args,
                               List<String> parameterNames, List<String> parameterTypes,
                               Object[] methodArgs, List<String> methodParameterNames,
                               List<String> methodParameterTypes, String[] callTypes,
                               Object[] callArgs, String[] localNames, String[] localTypes,
                               Object[] localValues, String localVariableMessage,
                               Object result, Throwable throwable, boolean voidResult,
                               boolean captureCallOutcome, boolean callHit,
                               Invocation invocation) {
            this.hitId = hitId;
            this.breakpointId = breakpointId;
            this.sequence = sequence;
            this.targetClass = targetClass;
            this.target = target;
            this.args = copy(args);
            this.parameterNames = copy(parameterNames);
            this.parameterTypes = copy(parameterTypes);
            this.methodArgs = copy(methodArgs);
            this.methodParameterNames = copy(methodParameterNames);
            this.methodParameterTypes = copy(methodParameterTypes);
            this.callTypes = copy(callTypes);
            this.callArgs = copy(callArgs);
            this.localNames = copy(localNames);
            this.localTypes = copy(localTypes);
            this.localValues = copy(localValues);
            this.localVariableMessage = localVariableMessage;
            this.result = result;
            this.throwable = throwable;
            this.voidResult = voidResult;
            this.captureCallOutcome = captureCallOutcome;
            this.callHit = callHit;
            this.invocation = invocation;
            this.stack = captureStack();
        }

        static CaptureRequest normal(String hitId, String breakpointId, int sequence,
                                     Class<?> targetClass, Object target, Object[] args,
                                     List<String> parameterNames, List<String> parameterTypes,
                                     String[] localNames, String[] localTypes,
                                     Object[] localValues, String localVariableMessage,
                                     Invocation invocation) {
            return new CaptureRequest(hitId, breakpointId, sequence, targetClass, target,
                    args, parameterNames, parameterTypes, args, parameterNames,
                    parameterTypes, null, null, localNames, localTypes, localValues,
                    localVariableMessage, null, null, false, false, false, invocation);
        }

        static CaptureRequest call(String hitId, String breakpointId, int sequence,
                                   Class<?> targetClass, Object target, Object[] methodArgs,
                                   List<String> methodParameterNames,
                                   List<String> methodParameterTypes, String[] callTypes,
                                   Object[] callArgs, String[] localNames, String[] localTypes,
                                   Object[] localValues, String localVariableMessage,
                                   Object result, Throwable throwable, boolean voidResult,
                                   boolean captureCallOutcome, Invocation invocation) {
            return new CaptureRequest(hitId, breakpointId, sequence, targetClass, target,
                    callArgs, null, null, methodArgs, methodParameterNames,
                    methodParameterTypes, callTypes, callArgs, localNames, localTypes,
                    localValues, localVariableMessage, result, throwable, voidResult,
                    captureCallOutcome, true, invocation);
        }

        RawHit capture() {
            return callHit
                    ? DebugHitStore.captureCall(hitId, breakpointId, sequence, targetClass,
                    target, methodArgs, methodParameterNames, methodParameterTypes,
                    callTypes, callArgs, localNames, localTypes, localValues,
                    localVariableMessage, result, throwable, voidResult,
                    captureCallOutcome, invocation, stack)
                    : DebugHitStore.capture(hitId, breakpointId, sequence, targetClass, target,
                    args, parameterNames, parameterTypes, localNames, localTypes,
                    localValues, localVariableMessage, invocation, stack);
        }

        RawHit failure() {
            Thread thread = Thread.currentThread();
            return new RawHit(hitId, breakpointId, sequence, System.currentTimeMillis(),
                    thread.getId(), thread.getName(), thread.getState().name(),
                    thread.isDaemon(), thread.getPriority(), stack,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), localVariableMessage,
                    null, null, null, invocation);
        }

        private static Object[] copy(Object[] values) {
            return values == null ? null : values.clone();
        }

        private static String[] copy(String[] values) {
            return values == null ? null : values.clone();
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? Collections.emptyList() : new ArrayList<>(values);
        }
    }

    static final class Invocation {
        private final String breakpointId;
        private volatile String status = "PENDING";
        private volatile Object result;
        private volatile Throwable throwable;
        private volatile String breakpointStatus = "NOT_AT_RETURN";
        private volatile Object breakpointResult;
        private volatile Throwable breakpointThrowable;
        private int hitCount;

        Invocation(String breakpointId) {
            this.breakpointId = breakpointId;
        }

        String breakpointId() {
            return breakpointId;
        }

        void markHit() {
            hitCount++;
        }

        void markSnapshot() {
            if ("NOT_AT_RETURN".equals(breakpointStatus)) {
                breakpointStatus = "SNAPSHOT";
            }
        }

        boolean hasHits() {
            return hitCount > 0;
        }

        void complete(Object value, Throwable error, boolean voidResult) {
            result = value;
            throwable = error;
            if (error != null) {
                status = "THREW";
            } else if (voidResult) {
                status = "VOID";
            } else {
                status = "RETURNED";
            }
        }

        void completeAtBreakpoint(Object value, Throwable error, boolean voidResult) {
            breakpointResult = value;
            breakpointThrowable = error;
            if (error != null) {
                breakpointStatus = "THREW";
            } else if (voidResult) {
                breakpointStatus = "VOID";
            } else {
                breakpointStatus = "RETURNED";
            }
        }

        String returnStatus() {
            return status;
        }
    }

    static final class RawHit {
        private final String hitId;
        private final String breakpointId;
        private final int sequence;
        private final long capturedAt;
        private final long threadId;
        private final String threadName;
        private final String threadState;
        private final boolean threadDaemon;
        private final int threadPriority;
        private final StackTraceElement[] stack;
        private List<CapturedValue> parameters;
        private List<CapturedValue> methodParameters;
        private List<CapturedValue> localVariables;
        private List<CapturedValue> instanceFields;
        private List<CapturedValue> staticFields;
        private final String localVariableMessage;
        private final String breakpointStatus;
        private final Object breakpointResult;
        private final Throwable breakpointThrowable;
        private final Invocation invocation;

        RawHit(String hitId, String breakpointId, int sequence, long capturedAt,
               long threadId, String threadName, String threadState,
               boolean threadDaemon, int threadPriority, StackTraceElement[] stack,
               List<CapturedValue> parameters, List<CapturedValue> methodParameters,
               List<CapturedValue> localVariables,
               List<CapturedValue> instanceFields, List<CapturedValue> staticFields,
               String localVariableMessage,
               String breakpointStatus, Object breakpointResult,
               Throwable breakpointThrowable, Invocation invocation) {
            this.hitId = hitId;
            this.breakpointId = breakpointId;
            this.sequence = sequence;
            this.capturedAt = capturedAt;
            this.threadId = threadId;
            this.threadName = threadName;
            this.threadState = threadState;
            this.threadDaemon = threadDaemon;
            this.threadPriority = threadPriority;
            this.stack = stack;
            this.parameters = parameters;
            this.methodParameters = methodParameters;
            this.localVariables = localVariables;
            this.instanceFields = instanceFields;
            this.staticFields = staticFields;
            this.localVariableMessage = localVariableMessage;
            this.breakpointStatus = breakpointStatus;
            this.breakpointResult = breakpointResult;
            this.breakpointThrowable = breakpointThrowable;
            this.invocation = invocation;
        }

        String hitId() {
            return hitId;
        }

        String breakpointId() {
            return breakpointId;
        }

        int sequence() {
            return sequence;
        }

        Invocation invocation() {
            return invocation;
        }

        void releaseCapturedValues() {
            parameters = Collections.emptyList();
            methodParameters = Collections.emptyList();
            localVariables = Collections.emptyList();
            instanceFields = Collections.emptyList();
            staticFields = Collections.emptyList();
        }
    }

    static final class CapturedValue {
        private final String name;
        private final String declaringClass;
        private final String declaredType;
        private final Object value;
        private final String error;

        CapturedValue(String name, String declaringClass,
                      String declaredType, Object value, String error) {
            this.name = name;
            this.declaringClass = declaringClass;
            this.declaredType = declaredType;
            this.value = value;
            this.error = error;
        }
    }

    private static final class HitRecord {
        private final RawHit raw;
        private final Map<String, Object> detail;
        private int bytes;

        HitRecord(RawHit raw, Map<String, Object> detail, int bytes) {
            this.raw = raw;
            this.detail = detail;
            this.bytes = bytes;
        }
    }

    private static final class ValueRenderer {
        private final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        private int remaining;
        private boolean truncated;

        ValueRenderer(int remaining) {
            this.remaining = remaining;
        }

        Map<String, Object> render(Object value, int depth) {
            if (value == null) {
                return value("NULL", null, null);
            }
            Class<?> type = value.getClass();
            if (!reserve(type.getName(), 48)) {
                return truncate(value);
            }
            if (value instanceof String) {
                String string = (String) value;
                if (!reserve(string, 24)) {
                    return truncate(value);
                }
                return value("SCALAR", type.getName(), string);
            }
            if (type == Boolean.class || type == Byte.class || type == Short.class
                    || type == Integer.class || type == Long.class
                    || type == Float.class || type == Double.class) {
                return value("SCALAR", type.getName(), value);
            }
            if (type == Character.class) {
                return value("SCALAR", type.getName(), String.valueOf((Character) value));
            }
            if (value instanceof Enum<?>) {
                String name = ((Enum<?>) value).name();
                reserve(name, 16);
                return value("SCALAR", type.getName(), name);
            }
            if (value instanceof Class<?>) {
                String name = ((Class<?>) value).getName();
                reserve(name, 16);
                return value("CLASS", type.getName(), name);
            }
            if (depth >= VALUE_DEPTH_LIMIT) {
                return truncate(value);
            }
            if (path.containsKey(value)) {
                Map<String, Object> cycle = value("REFERENCE", type.getName(), "<cycle>");
                cycle.put("cycle", true);
                return cycle;
            }
            path.put(value, Boolean.TRUE);
            try {
                if (type.isArray()) {
                    return renderArray(value, depth);
                }
                return renderObject(value, depth);
            } finally {
                path.remove(value);
            }
        }

        private Map<String, Object> renderArray(Object array, int depth) {
            int length = Array.getLength(array);
            Map<String, Object> result = value("ARRAY", array.getClass().getName(), null);
            result.remove("value");
            result.put("length", length);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < length; index++) {
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                items.add(render(Array.get(array, index), depth + 1));
            }
            result.put("items", items);
            result.put("truncated", items.size() < length);
            return result;
        }

        private Map<String, Object> renderObject(Object object, int depth) {
            Class<?> type = object.getClass();
            Map<String, Object> result = value("OBJECT", type.getName(), null);
            result.remove("value");
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Class<?> current = type;
                 current != null && current != Object.class;
                 current = current.getSuperclass()) {
                Field[] declared;
                try {
                    declared = current.getDeclaredFields();
                } catch (Throwable error) {
                    fields.add(fieldError("<fields>", current, null, error));
                    continue;
                }
                for (Field field : declared) {
                    if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (remaining <= 0) {
                        truncated = true;
                        result.put("truncated", true);
                        result.put("fields", fields);
                        return result;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", field.getName());
                    item.put("declaringClass", current.getName());
                    item.put("declaredType", field.getType().getTypeName());
                    try {
                        if (!field.isAccessible()) {
                            field.setAccessible(true);
                        }
                        item.put("value", render(field.get(object), depth + 1));
                        item.put("error", null);
                    } catch (Throwable error) {
                        item.put("value", null);
                        item.put("error", describe(error));
                    }
                    fields.add(item);
                }
            }
            result.put("fields", fields);
            result.put("truncated", false);
            return result;
        }

        private Map<String, Object> fieldError(
                String name, Class<?> declaringClass, Class<?> declaredType, Throwable error) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("declaringClass", declaringClass.getName());
            result.put("declaredType", declaredType == null ? null : declaredType.getTypeName());
            result.put("value", null);
            result.put("error", describe(error));
            return result;
        }

        private Map<String, Object> truncate(Object value) {
            truncated = true;
            remaining = Math.max(0, remaining);
            return truncatedValue(value);
        }

        private boolean reserve(String value, int overhead) {
            int bytes = overhead;
            if (value != null) {
                bytes += value.getBytes(StandardCharsets.UTF_8).length;
            }
            if (remaining < bytes) {
                remaining = 0;
                truncated = true;
                return false;
            }
            remaining -= bytes;
            return true;
        }

        private boolean reserveText(String value, int overhead) {
            return reserve(value, overhead);
        }

        private Map<String, Object> value(String kind, String type, Object value) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", kind);
            result.put("type", type);
            result.put("value", value);
            return result;
        }
    }
}
