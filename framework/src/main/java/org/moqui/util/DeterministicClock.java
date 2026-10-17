package org.moqui.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinator-assigned application time. Never use this clock for deadlines or profiling. */
public final class DeterministicClock {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ReentrantLock LANE = new ReentrantLock(true);
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static class Process { static final DeterministicClock CLOCK = new DeterministicClock(settings()); }
    private final URI hook;
    private final String run, engine;
    private final BufferedWriter log;
    private final HttpClient client;
    private final int timeoutSeconds;
    private final ThreadLocal<Deque<Operation>> operations = ThreadLocal.withInitial(ArrayDeque::new);
    private volatile boolean failed;

    private static Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>(System.getenv());
        for (String key : List.of("MOQUI_DETERMINISTIC", "MOQUI_DETERMINISTIC_HOOK", "MOQUI_DETERMINISTIC_RUN",
                "MOQUI_DETERMINISTIC_ENGINE", "MOQUI_DETERMINISTIC_LOG", "MOQUI_DETERMINISTIC_TIMEOUT_SECONDS")) {
            if (System.getProperty(key) != null) settings.put(key, System.getProperty(key));
        }
        return settings;
    }
    public static boolean isEnabled() {
        String flag = System.getProperty("MOQUI_DETERMINISTIC", System.getenv("MOQUI_DETERMINISTIC"));
        if (flag != null && !flag.equals("true") && !flag.equals("false"))
            throw new IllegalArgumentException("MOQUI_DETERMINISTIC must be true or false");
        return "true".equals(flag);
    }
    /** Normal Elastic client/index startup defaults on; background Elastic logging stays disabled. */
    public static boolean isElasticEnabled() {
        String flag = System.getProperty("MOQUI_DETERMINISTIC_ELASTIC", System.getenv("MOQUI_DETERMINISTIC_ELASTIC"));
        if (flag != null && !flag.equals("true") && !flag.equals("false"))
            throw new IllegalArgumentException("MOQUI_DETERMINISTIC_ELASTIC must be true or false");
        return flag == null || "true".equals(flag);
    }
    public static DeterministicClock get() { return Process.CLOCK; }
    public static void lockLane() { LANE.lock(); }
    public static void unlockLane() { LANE.unlock(); }
    public static long nowMillis() {
        if (!isEnabled()) return System.currentTimeMillis();
        DeterministicClock clock = get();
        clock.checkHealthy();
        for (Operation operation : clock.operations.get()) if (!operation.suspended) return operation.time;
        throw new IllegalStateException("Deterministic time requires a transaction or explicit coordinator operation");
    }
    public boolean hasTime() {
        for (Operation operation : operations.get()) if (!operation.suspended) return true;
        return false;
    }
    public boolean hasTransaction() {
        // Suspended parents also prevent queued async work from running.
        for (Operation operation : operations.get()) if (operation.transaction) return true;
        return false;
    }
    public static Map<String, String> bootstrapArtifact() {
        return Map.of("type", "AT_OTHER", "sub_type", "transaction", "name", "bootstrap");
    }
    public static Operation bootstrap() {
        return isEnabled() ? get().begin(List.of(bootstrapArtifact()), false) : null;
    }
    // Package-private for isolated protocol tests. Production has exactly one process instance, across ECF restarts.
    DeterministicClock(Map<String, String> settings) {
        try {
            hook = URI.create(required(settings, "MOQUI_DETERMINISTIC_HOOK"));
            if (!("http".equals(hook.getScheme()) || "https".equals(hook.getScheme())) || hook.getHost() == null ||
                    hook.getUserInfo() != null || hook.getQuery() != null || hook.getFragment() != null ||
                    !"/transaction".equals(hook.getPath())) throw new IllegalArgumentException("Invalid deterministic hook URL");
            timeoutSeconds = Integer.parseInt(settings.getOrDefault("MOQUI_DETERMINISTIC_TIMEOUT_SECONDS", "120"));
            if (timeoutSeconds < 1 || timeoutSeconds > 3600)
                throw new IllegalArgumentException("MOQUI_DETERMINISTIC_TIMEOUT_SECONDS must be between 1 and 3600");
            run = required(settings, "MOQUI_DETERMINISTIC_RUN");
            engine = required(settings, "MOQUI_DETERMINISTIC_ENGINE");
            if (!"java".equals(engine)) throw new IllegalArgumentException("MOQUI_DETERMINISTIC_ENGINE must be java");
            Path path = Path.of(required(settings, "MOQUI_DETERMINISTIC_LOG"));
            if (!path.isAbsolute()) throw new IllegalArgumentException("MOQUI_DETERMINISTIC_LOG must be absolute");
            log = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (IOException e) { throw new IllegalStateException("Cannot open deterministic log", e); }
    }
    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Required deterministic setting: " + key);
        return value;
    }
    private void checkHealthy() {
        if (failed) throw new IllegalStateException("Deterministic coordinator/log failed; execution must stop");
    }
    public Operation begin(List<Map<String, String>> artifacts, boolean transaction) {
        LANE.lock();
        try {
            checkHealthy();
            List<Map<String, String>> stack = new ArrayList<>();
            for (Map<String, String> artifact : artifacts) {
                // Only stable artifact identity; never copy parameters, user context, or exception details.
                Map<String, String> safe = new LinkedHashMap<>();
                for (String key : List.of("type", "sub_type", "name")) {
                    String value = artifact.get(key);
                    if (value == null) throw new IllegalArgumentException("Deterministic artifact identity must contain strings");
                    safe.put(key, value);
                }
                stack.add(safe);
            }
            if (stack.isEmpty()) stack.add(bootstrapArtifact());
            long sequence = SEQUENCE.incrementAndGet();
            Map<String, Object> event = event(sequence, "begin");
            Operation parent = operations.get().peek();
            event.put("parent_sequence", parent == null ? null : parent.sequence);
            event.put("artifact", stack.get(0)); // innermost first, as in Moqui's artifact stack
            event.put("artifact_stack", stack);
            JsonNode response = post(event);
            JsonNode id = response.get("operation_id"), time = response.get("time_epoch_ms");
            if (response.size() != 2 || id == null || !id.isTextual() || !id.asText().matches("[A-Za-z0-9._:-]{1,128}") ||
                    time == null || !time.isIntegralNumber() || !time.canConvertToLong())
                throw new IllegalStateException("Invalid deterministic begin response");
            Operation operation = new Operation(this, sequence, id.asText(), time.longValue(), transaction);
            event.put("operation_id", operation.id);
            event.put("time_epoch_ms", operation.time);
            append(event);
            operations.get().push(operation);
            return operation;
        } catch (RuntimeException e) {
            failed = true;
            LANE.unlock();
            throw e;
        }
    }
    private Map<String, Object> event(long sequence, String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", 1); event.put("run_id", run); event.put("engine", engine);
        event.put("sequence", sequence); event.put("event", type);
        return event;
    }
    private JsonNode post(Map<String, Object> event) {
        try {
            HttpRequest request = HttpRequest.newBuilder(hook).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(event))).build();
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try { response = pending.get(timeoutSeconds, TimeUnit.SECONDS); }
            catch (TimeoutException e) {
                pending.cancel(true);
                throw new IllegalStateException("Deterministic hook exceeded real deadline");
            }
            if (response.statusCode() != 200 || response.body().length() > 16384)
                throw new IllegalStateException("Deterministic hook returned invalid status/body");
            JsonNode node = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body());
            if (node == null || !node.isObject()) throw new IllegalStateException("Invalid deterministic hook JSON");
            return node;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Deterministic hook interrupted");
        } catch (IOException | ExecutionException e) {
            // Do not include the response body, URL, or transport exception (may contain sensitive content).
            throw new IllegalStateException("Deterministic hook transport/JSON failure");
        }
    }
    private void append(Map<String, Object> event) {
        try { log.write(JSON.writeValueAsString(event)); log.newLine(); log.flush(); }
        catch (IOException e) { throw new IllegalStateException("Deterministic log write failed"); }
    }
    public static final class Operation {
        private final DeterministicClock clock;
        public final long sequence, time;
        private final String id;
        private final boolean transaction;
        public boolean suspended;
        private boolean completed;
        public volatile int completionStatus = -1;
        private Operation(DeterministicClock clock, long sequence, String id, long time, boolean transaction) {
            this.clock = clock; this.sequence = sequence; this.id = id; this.time = time; this.transaction = transaction;
        }
        public void complete(String outcome) {
            if (completed) return;
            if (!LANE.isHeldByCurrentThread() || clock.operations.get().peek() != this)
                throw new IllegalStateException("Deterministic operation completed outside its lane/stack");
            try {
                clock.checkHealthy();
                if (!List.of("committed", "rolled_back", "begin_failed").contains(outcome))
                    throw new IllegalStateException("Unknown deterministic transaction outcome");
                Map<String, Object> event = clock.event(sequence, "complete");
                event.put("operation_id", id); event.put("outcome", outcome);
                clock.append(event); // actual outcome is logged even if completion acknowledgement fails
                JsonNode response = clock.post(event);
                if (response.size() != 1 || !response.path("ok").isBoolean() || !response.path("ok").booleanValue())
                    throw new IllegalStateException("Invalid deterministic completion response");
            } catch (RuntimeException e) { clock.failed = true; throw e; }
            finally { completed = true; clock.operations.get().pop(); LANE.unlock(); }
        }
    }
}
