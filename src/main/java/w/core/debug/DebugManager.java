package w.core.debug;

import w.Global;
import w.core.ClassTargetResolver;
import w.core.model.TransformApplyResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public seam for bounded, non-suspending runtime line breakpoints.
 */
public final class DebugManager implements DebugHitStore.Listener {
    private static final DebugManager INSTANCE = new DebugManager();
    private static final int DEFAULT_TTL_SECONDS = 600;
    private static final int MIN_TTL_SECONDS = 60;
    private static final int MAX_TTL_SECONDS = 3600;
    private static final int DEFAULT_MAX_HITS = 10;
    private static final int MIN_MAX_HITS = 1;
    private static final int MAX_MAX_HITS = 100;
    private static final int MAX_ACTIVE = 20;
    private static final int MAX_ACTIVE_PER_CLASS = 5;
    private static final long MAX_WAIT_MS = 30000L;
    private static final long TERMINAL_RETENTION_MS = TimeUnit.MINUTES.toMillis(10);

    private final ConcurrentHashMap<String, BreakpointRecord> records = new ConcurrentHashMap<>();
    private final DebugClassInspector inspector = new DebugClassInspector();
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(2, new DebugThreadFactory());
    private final DebugHitStore hitStore = new DebugHitStore(this);

    private DebugManager() {
    }

    public static DebugManager getInstance() {
        return INSTANCE;
    }

    public List<Map<String, Object>> searchLoadedClasses(String query, int requestedLimit) {
        requireInstrumentation();
        cleanup();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 50 : requestedLimit, 200));
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Class<?> type : Global.instrumentation.getAllLoadedClasses()) {
            if (type == null || (!needle.isEmpty()
                    && !type.getName().toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            ClassLoader loader = type.getClassLoader();
            String hash = ClassTargetResolver.identityHash(loader);
            String key = type.getName() + "@" + hash;
            if (unique.containsKey(key)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", type.getName());
            item.put("classLoaderHash", hash);
            item.put("classLoaderName", classLoaderName(loader));
            item.put("modifiable", Global.isModifiableClass(type));
            unique.put(key, item);
        }
        List<Map<String, Object>> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("className"))));
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, limit));
    }

    public Map<String, Object> inspectClass(String className, String classLoaderHash)
            throws Exception {
        cleanup();
        return inspector.inspect(resolve(className, classLoaderHash)).toMap();
    }

    public Map<String, Object> addBreakpoint(String className, String classLoaderHash,
                                             int displayLine, boolean adjustToNearest,
                                             int requestedTtlSeconds, int requestedMaxHits)
            throws Exception {
        cleanup();
        if (displayLine <= 0) {
            throw new IllegalArgumentException("displayLine must be positive");
        }
        int ttlSeconds = requestedTtlSeconds <= 0 ? DEFAULT_TTL_SECONDS : requestedTtlSeconds;
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("ttlSeconds must be between 60 and 3600");
        }
        int maxHits = requestedMaxHits <= 0 ? DEFAULT_MAX_HITS : requestedMaxHits;
        if (maxHits < MIN_MAX_HITS || maxHits > MAX_MAX_HITS) {
            throw new IllegalArgumentException("maxHits must be between 1 and 100");
        }

        Class<?> target = resolve(className, classLoaderHash);
        DebugClassInspector.BreakpointLocation location =
                inspector.inspect(target).locate(displayLine, adjustToNearest);
        synchronized (this) {
            BreakpointRecord duplicate = findDuplicate(target, location);
            if (duplicate != null) {
                Map<String, Object> result = duplicate.toMap();
                result.put("outcome", "ALREADY_EXISTS");
                return result;
            }
            enforceCapacity(target);
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            BreakpointRecord record = new BreakpointRecord(id, target, location, now,
                    now + TimeUnit.SECONDS.toMillis(ttlSeconds), maxHits);
            DebugTransformer transformer = new DebugTransformer(
                    record.id, target.getName(), target.getClassLoader(),
                    location.methodName, location.descriptor, location.originalLine);
            record.captureTransformer = transformer;
            records.put(id, record);
            try {
                Global.addTransformer(transformer);
                record.transformerAttached = true;
                transformer.beginApply(target);
                Global.addActiveTransformer(target, transformer);
                TransformApplyResult apply = transformer.getApplyResult(target);
                if (apply == null || !apply.isCalled() || !apply.isSuccess()) {
                    throw new IllegalStateException(
                            apply == null ? "Debug transformer was not called" : apply.getMessage());
                }
                record.parameterNames = transformer.parameterNames();
                record.parameterTypes = transformer.parameterTypes();
                record.expiryTask = executor.schedule(
                        () -> startDisarm(record, CompletionReason.TTL_EXPIRED, false, null),
                        ttlSeconds, TimeUnit.SECONDS);
                publishBreakpoint(record);
                Map<String, Object> result = record.toMap();
                result.put("outcome", "CREATED");
                return result;
            } catch (Throwable error) {
                startDisarm(record, CompletionReason.INSTALL_FAILED, true,
                        "Breakpoint installation failed: " + describe(error));
                if (error instanceof Exception) {
                    throw (Exception) error;
                }
                throw new IllegalStateException(error);
            }
        }
    }

    public Map<String, Object> getBreakpoint(String id, long requestedTimeoutMs) {
        cleanup();
        BreakpointRecord record = requireRecord(id);
        long timeoutMs = Math.max(0, Math.min(requestedTimeoutMs, MAX_WAIT_MS));
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (record.monitor) {
            long observedVersion = record.version;
            while (record.version == observedVersion && timeoutMs > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    record.monitor.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return record.toMap();
        }
    }

    public List<Map<String, Object>> listBreakpoints() {
        cleanup();
        List<BreakpointRecord> sorted = new ArrayList<>(records.values());
        sorted.sort(Comparator.comparingLong(
                (BreakpointRecord record) -> record.createdAt).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (BreakpointRecord record : sorted) {
            result.add(record.toMap());
        }
        return result;
    }

    public List<Map<String, Object>> listHits(String breakpointId) {
        cleanup();
        BreakpointRecord record = requireRecord(breakpointId);
        return hitStore.list(record.id);
    }

    public Map<String, Object> getHit(String hitId) {
        cleanup();
        if (hitId == null || hitId.trim().isEmpty()) {
            throw new IllegalArgumentException("hitId is required");
        }
        return hitStore.get(hitId.trim());
    }

    public Map<String, Object> removeBreakpoint(String id) {
        BreakpointRecord record = requireRecord(id);
        startDisarm(record, CompletionReason.USER_REMOVED, false, null);
        return record.toMap();
    }

    public synchronized Map<String, Object> clearHistory() {
        int removedCount = 0;
        int retainedAttachedCount = 0;
        for (BreakpointRecord record : new ArrayList<>(records.values())) {
            if (record.status != Status.DISARMED && record.status != Status.ERROR) {
                continue;
            }
            if (record.transformerAttached) {
                retainedAttachedCount++;
            } else if (records.remove(record.id, record)) {
                hitStore.removeBreakpoint(record.id);
                removedCount++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removedCount", removedCount);
        result.put("retainedAttachedCount", retainedAttachedCount);
        return result;
    }

    public void reset() {
        for (BreakpointRecord record : records.values()) {
            ScheduledFuture<?> task = record.expiryTask;
            if (task != null) {
                task.cancel(false);
            }
            synchronized (record.monitor) {
                record.status = Status.DISARMED;
                record.completionReason = CompletionReason.RESET;
                record.transformerAttached = false;
                record.completedAt = System.currentTimeMillis();
                changed(record);
            }
        }
        records.clear();
        hitStore.clear();
    }

    public Object enter(String id) {
        BreakpointRecord record = records.get(id);
        if (record == null || record.status != Status.ARMED
                || record.disarmRequested.get()) {
            return null;
        }
        return new DebugHitStore.Invocation(id);
    }

    public void hit(String id) {
        DebugHitStore.Invocation invocation = new DebugHitStore.Invocation(id);
        hit(id, invocation, null, new Object[0]);
    }

    public void hit(String id, Object opaqueToken, Object target, Object[] args) {
        hit(id, opaqueToken, target, args, null, null, null);
    }

    public void hit(String id, Object opaqueToken, Object target, Object[] args,
                    String[] localNames, String[] localTypes, Object[] localValues) {
        recordHit(id, opaqueToken, target, args,
                null, null, localNames, localTypes, localValues,
                null, null, false, false);
    }

    public void callHit(
            String id, Object opaqueToken, Object target, Object[] methodArgs,
            String[] callTypes, Object[] callArgs,
            String[] localNames, String[] localTypes, Object[] localValues,
            Object result, Throwable throwable, boolean voidResult,
            boolean methodExitLine) {
        recordHit(id, opaqueToken, target, methodArgs,
                callTypes, callArgs, localNames, localTypes, localValues,
                result, throwable, voidResult, true, methodExitLine);
    }

    private void recordHit(
            String id, Object opaqueToken, Object target, Object[] methodArgs,
            String[] callTypes, Object[] callArgs,
            String[] localNames, String[] localTypes, Object[] localValues,
            Object result, Throwable throwable, boolean voidResult,
            boolean callHit) {
        recordHit(id, opaqueToken, target, methodArgs,
                callTypes, callArgs, localNames, localTypes, localValues,
                result, throwable, voidResult, callHit, false);
    }

    private void recordHit(
            String id, Object opaqueToken, Object target, Object[] methodArgs,
            String[] callTypes, Object[] callArgs,
            String[] localNames, String[] localTypes, Object[] localValues,
            Object result, Throwable throwable, boolean voidResult,
            boolean callHit, boolean methodExitLine) {
        BreakpointRecord record = records.get(id);
        if (!(opaqueToken instanceof DebugHitStore.Invocation)) {
            return;
        }
        DebugHitStore.Invocation invocation = (DebugHitStore.Invocation) opaqueToken;
        if (!id.equals(invocation.breakpointId())) {
            return;
        }
        if (record == null || record.status != Status.ARMED
                || record.disarmRequested.get()) {
            return;
        }
        if (System.currentTimeMillis() >= record.expiresAt) {
            requestDisarmAsync(record, CompletionReason.TTL_EXPIRED, false, null);
            return;
        }
        while (record.status == Status.ARMED && !record.disarmRequested.get()) {
            int current = record.hitCount.get();
            if (current >= record.maxHits) {
                requestDisarmAsync(record, CompletionReason.MAX_HITS, false, null);
                return;
            }
            int next = current + 1;
            if (record.hitCount.compareAndSet(current, next)) {
                invocation.markHit();
                if (!callHit) {
                    invocation.markSnapshot();
                }
                String hitId = record.id + ":" + next;
                record.latestHitId = hitId;
                List<String> names = record.captureTransformer == null
                        ? record.parameterNames
                        : record.captureTransformer.parameterNames();
                List<String> types = record.captureTransformer == null
                        ? record.parameterTypes
                        : record.captureTransformer.parameterTypes();
                String message = record.captureTransformer == null
                        ? null : record.captureTransformer.breakpointVariableMessage();
                DebugHitStore.CaptureRequest request = callHit
                        ? DebugHitStore.CaptureRequest.call(
                        hitId, record.id, next, record.targetClass, target,
                        methodArgs, names, types, callTypes, callArgs,
                        localNames, localTypes, localValues, message,
                        result, throwable, voidResult, !methodExitLine,
                        invocation)
                        : DebugHitStore.CaptureRequest.normal(
                        hitId, record.id, next, record.targetClass, target, methodArgs,
                        names, types, localNames, localTypes, localValues,
                        message, invocation);
                if (!hitStore.submit(request)) {
                    recordCaptureErrorAsync(request.failure(), "Capture queue is full");
                }
                try {
                    executor.execute(() -> finishHit(record, next));
                } catch (Throwable ignored) {
                    // Counter remains truthful even if notification scheduling fails.
                }
                return;
            }
        }
    }

    public void exit(Object opaqueToken, Object result,
                     Throwable throwable, boolean voidResult) {
        if (!(opaqueToken instanceof DebugHitStore.Invocation)) {
            return;
        }
        DebugHitStore.Invocation invocation = (DebugHitStore.Invocation) opaqueToken;
        invocation.complete(result, throwable, voidResult);
        if (!invocation.hasHits()) {
            return;
        }
        if (!hitStore.complete(invocation)) {
            try {
                executor.execute(() -> hitStore.completeNow(invocation));
            } catch (Throwable ignored) {
                // getHit also materializes the final invocation state on demand.
            }
        }
    }

    public void breakpointExit(Object opaqueToken, Object result,
                               Throwable throwable, boolean voidResult) {
        if (!(opaqueToken instanceof DebugHitStore.Invocation)) {
            return;
        }
        DebugHitStore.Invocation invocation = (DebugHitStore.Invocation) opaqueToken;
        invocation.completeAtBreakpoint(result, throwable, voidResult);
    }

    private void finishHit(BreakpointRecord record, int hitCount) {
        changed(record);
        publishBreakpoint(record);
        if (hitCount >= record.maxHits) {
            startDisarm(record, CompletionReason.MAX_HITS, false, null);
        }
    }

    private void startDisarm(BreakpointRecord record, CompletionReason reason,
                             boolean terminalError, String error) {
        record.disarmRequested.set(true);
        synchronized (record.monitor) {
            if (record.status == Status.DISARMED
                    || record.status == Status.DISARMING
                    || (record.status == Status.ERROR && !record.transformerAttached)) {
                return;
            }
            record.status = Status.DISARMING;
            record.completionReason = reason;
            if (error != null) {
                record.error = error;
            }
            record.terminalError = terminalError;
            if (record.expiryTask != null) {
                record.expiryTask.cancel(false);
            }
            changed(record);
        }
        publishBreakpoint(record);
        if (record.uninstallScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(() -> uninstall(record));
            } catch (Throwable schedulingError) {
                synchronized (record.monitor) {
                    record.status = Status.ERROR;
                    record.error = "Transformer removal was not scheduled: "
                            + describe(schedulingError);
                    record.completedAt = System.currentTimeMillis();
                    changed(record);
                }
                publishBreakpoint(record);
                scheduleCleanup();
            }
        }
    }

    private void requestDisarmAsync(BreakpointRecord record, CompletionReason reason,
                                    boolean terminalError, String error) {
        if (!record.disarmRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> startDisarm(record, reason, terminalError, error));
        } catch (Throwable ignored) {
            record.disarmRequested.set(false);
        }
    }

    private void uninstall(BreakpointRecord record) {
        List<String> errors = new ArrayList<>();
        try {
            if (record.transformerAttached) {
                errors.addAll(Global.deleteTransformerChecked(UUID.fromString(record.id)));
                record.transformerAttached =
                        Global.isTransformerInstalled(UUID.fromString(record.id));
            }
        } catch (Throwable error) {
            errors.add(describe(error));
        }
        synchronized (record.monitor) {
            if (errors.isEmpty()) {
                record.status = record.terminalError ? Status.ERROR : Status.DISARMED;
            } else {
                record.status = Status.ERROR;
                record.error = "Transformer removal failed: " + errors;
            }
            record.completedAt = System.currentTimeMillis();
            changed(record);
        }
        publishBreakpoint(record);
        scheduleCleanup();
    }

    private BreakpointRecord findDuplicate(
            Class<?> target, DebugClassInspector.BreakpointLocation location) {
        for (BreakpointRecord record : records.values()) {
            if ((record.status == Status.ARMED || record.status == Status.DISARMING)
                    && record.targetClass == target
                    && record.location.originalLine == location.originalLine
                    && record.location.methodName.equals(location.methodName)
                    && record.location.descriptor.equals(location.descriptor)) {
                return record;
            }
        }
        return null;
    }

    private void enforceCapacity(Class<?> target) {
        int active = 0;
        int sameClass = 0;
        for (BreakpointRecord record : records.values()) {
            if (record.status == Status.ARMED || record.status == Status.DISARMING) {
                active++;
                if (record.targetClass == target) {
                    sameClass++;
                }
            }
        }
        if (active >= MAX_ACTIVE) {
            throw new IllegalStateException("At most 20 active debug breakpoints are allowed");
        }
        if (sameClass >= MAX_ACTIVE_PER_CLASS) {
            throw new IllegalStateException(
                    "At most 5 active debug breakpoints are allowed per class");
        }
    }

    private Class<?> resolve(String className, String classLoaderHash) {
        requireInstrumentation();
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("className is required");
        }
        ClassTargetResolver.Resolution resolution =
                new ClassTargetResolver(Global.instrumentation)
                        .resolve(className.trim(), classLoaderHash);
        if (!resolution.isSuccess()) {
            throw new IllegalArgumentException(resolution.getError());
        }
        if (!Global.isModifiableClass(resolution.getTarget().getType())) {
            throw new IllegalArgumentException("Target class is not modifiable: " + className);
        }
        return resolution.getTarget().getType();
    }

    private void requireInstrumentation() {
        if (Global.instrumentation == null) {
            throw new IllegalStateException("Instrumentation is not initialized");
        }
    }

    private BreakpointRecord requireRecord(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("breakpointId is required");
        }
        BreakpointRecord record = records.get(id.trim());
        if (record == null) {
            throw new IllegalArgumentException("Debug breakpoint not found: " + id);
        }
        return record;
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - TERMINAL_RETENTION_MS;
        for (BreakpointRecord record : records.values()) {
            if ((record.status == Status.DISARMED || record.status == Status.ERROR)
                    && record.completedAt > 0 && record.completedAt < cutoff
                    && !record.transformerAttached) {
                if (records.remove(record.id, record)) {
                    hitStore.removeBreakpoint(record.id);
                }
            }
        }
    }

    private void scheduleCleanup() {
        try {
            executor.schedule(this::cleanup, TERMINAL_RETENTION_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // A future public operation also performs cleanup.
        }
    }

    private void publishBreakpoint(BreakpointRecord record) {
        if (records.get(record.id) != record) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "DEBUG_BREAKPOINT");
        event.put("timestamp", System.currentTimeMillis());
        event.put("breakpoint", record.toMap());
        Global.publishEvent(event);
    }

    private void publishHit(DebugHitStore.RawHit raw) {
        if (!accepts(raw.breakpointId())) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "DEBUG_HIT");
        event.put("timestamp", System.currentTimeMillis());
        event.put("breakpointId", raw.breakpointId());
        event.put("hitId", raw.hitId());
        event.put("sequence", raw.sequence());
        event.put("returnStatus", raw.invocation().returnStatus());
        Global.publishEvent(event);
    }

    @Override
    public boolean accepts(String breakpointId) {
        return records.containsKey(breakpointId);
    }

    @Override
    public void onStored(DebugHitStore.RawHit raw, boolean captureError) {
        BreakpointRecord record = records.get(raw.breakpointId());
        if (record == null) {
            return;
        }
        record.retainedHitCount.incrementAndGet();
        if (captureError) {
            record.captureErrorCount.incrementAndGet();
        }
        notifyHit(record, raw, true);
    }

    @Override
    public void onUpdated(DebugHitStore.RawHit raw) {
        BreakpointRecord record = records.get(raw.breakpointId());
        if (record != null) {
            notifyHit(record, raw, false);
        }
    }

    @Override
    public void onEvicted(DebugHitStore.RawHit raw) {
        BreakpointRecord record = records.get(raw.breakpointId());
        if (record == null) {
            return;
        }
        decrementRetained(record);
        record.evictedHitCount.incrementAndGet();
        try {
            executor.execute(() -> {
                changed(record);
                publishBreakpoint(record);
            });
        } catch (Throwable ignored) {
            // Counters remain accurate even if notification scheduling fails.
        }
    }

    @Override
    public void onRemoved(DebugHitStore.RawHit raw) {
        BreakpointRecord record = records.get(raw.breakpointId());
        if (record != null) {
            decrementRetained(record);
        }
    }

    private void notifyHit(BreakpointRecord record, DebugHitStore.RawHit raw,
                           boolean breakpointChanged) {
        try {
            executor.execute(() -> {
                if (breakpointChanged) {
                    changed(record);
                    publishBreakpoint(record);
                }
                publishHit(raw);
            });
        } catch (Throwable ignored) {
            // Captured data remains readable through MCP.
        }
    }

    private void recordCaptureErrorAsync(DebugHitStore.RawHit raw, String error) {
        try {
            executor.execute(() -> hitStore.storeCaptureError(raw, error));
        } catch (Throwable ignored) {
            BreakpointRecord record = records.get(raw.breakpointId());
            if (record != null) {
                record.captureErrorCount.incrementAndGet();
            }
        }
    }

    private static void decrementRetained(BreakpointRecord record) {
        while (true) {
            int current = record.retainedHitCount.get();
            if (current <= 0 || record.retainedHitCount.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    private static void changed(BreakpointRecord record) {
        synchronized (record.monitor) {
            record.version++;
            record.monitor.notifyAll();
        }
    }

    private static String classLoaderName(ClassLoader loader) {
        return loader == null ? "bootstrap"
                : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private enum Status {
        ARMED, DISARMING, DISARMED, ERROR
    }

    private enum CompletionReason {
        MAX_HITS, TTL_EXPIRED, USER_REMOVED, RESET, INSTALL_FAILED
    }

    private static final class BreakpointRecord {
        private final String id;
        private final Class<?> targetClass;
        private final DebugClassInspector.BreakpointLocation location;
        private final long createdAt;
        private final long expiresAt;
        private final int maxHits;
        private final Object monitor = new Object();
        private final AtomicBoolean disarmRequested = new AtomicBoolean();
        private final AtomicBoolean uninstallScheduled = new AtomicBoolean();
        private final AtomicInteger hitCount = new AtomicInteger();
        private final AtomicInteger retainedHitCount = new AtomicInteger();
        private final AtomicInteger evictedHitCount = new AtomicInteger();
        private final AtomicInteger captureErrorCount = new AtomicInteger();
        private volatile Status status = Status.ARMED;
        private volatile CompletionReason completionReason;
        private volatile boolean transformerAttached;
        private volatile boolean terminalError;
        private volatile long completedAt;
        private volatile long version;
        private volatile String error;
        private volatile String latestHitId;
        private volatile List<String> parameterNames = new ArrayList<>();
        private volatile List<String> parameterTypes = new ArrayList<>();
        private volatile DebugTransformer captureTransformer;
        private volatile ScheduledFuture<?> expiryTask;

        private BreakpointRecord(String id, Class<?> targetClass,
                                 DebugClassInspector.BreakpointLocation location,
                                 long createdAt, long expiresAt, int maxHits) {
            this.id = id;
            this.targetClass = targetClass;
            this.location = location;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.maxHits = maxHits;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("breakpointId", id);
            result.put("transformerId", id);
            result.put("status", status.name());
            result.put("completionReason",
                    completionReason == null ? null : completionReason.name());
            result.put("transformerAttached", transformerAttached);
            result.put("className", targetClass.getName());
            result.put("classLoaderHash",
                    ClassTargetResolver.identityHash(targetClass.getClassLoader()));
            result.put("classLoaderName", classLoaderName(targetClass.getClassLoader()));
            result.put("methodName", location.methodName);
            result.put("methodDescriptor", location.descriptor);
            result.put("requestedDisplayLine", location.requestedDisplayLine);
            result.put("actualDisplayLine", location.actualDisplayLine);
            result.put("originalBytecodeLine", location.originalLine);
            result.put("lineAdjusted",
                    location.requestedDisplayLine != location.actualDisplayLine);
            result.put("createdAt", createdAt);
            result.put("expiresAt", expiresAt);
            result.put("hitCount", hitCount.get());
            result.put("maxHits", maxHits);
            result.put("remainingHits", Math.max(0, maxHits - hitCount.get()));
            result.put("retainedHitCount", retainedHitCount.get());
            result.put("evictedHitCount", evictedHitCount.get());
            result.put("captureErrorCount", captureErrorCount.get());
            result.put("latestHitId", latestHitId);
            result.put("remainingMs", status == Status.ARMED
                    ? Math.max(0, expiresAt - System.currentTimeMillis()) : 0);
            result.put("completedAt", completedAt == 0 ? null : completedAt);
            result.put("error", error);
            return result;
        }
    }

    private static final class DebugThreadFactory implements ThreadFactory {
        private int count;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "swapper-online-debug-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    }
}
