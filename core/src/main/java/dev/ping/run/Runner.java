package dev.ping.run;

import dev.ping.http.AssertionResult;
import dev.ping.http.HttpEngine;
import dev.ping.http.RequestSpec;
import dev.ping.http.ResponseData;
import dev.ping.rpc.RpcException;
import dev.ping.store.CollectionNode;
import dev.ping.store.EnvironmentRef;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;
import dev.ping.vars.Variables;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs every request in a collection, in sidebar order, and reports what happened.
 *
 * <p>No RPC and no argv in here: the app's {@code run.collection} and the CLI are thin
 * adapters over this, so they cannot disagree about what a run does.
 *
 * <p>Requests run one after another, not concurrently. A later request will depend on values
 * an earlier one captures (phase 16), and the runtime map below is what carries them. A
 * transport failure marks that request errored and the run continues: one dead endpoint
 * should not hide the results of the rest.
 */
public final class Runner {

    /** Called after each request finishes, on the thread that is running the collection. */
    @FunctionalInterface
    public interface Listener {
        void onRequest(int index, int total, RequestResult result);
    }

    private final YamlStore store;
    private final HttpEngine engine;

    public Runner(YamlStore store, HttpEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    public RunResult run(Path root, String collection, RunOptions options, Listener listener) {
        long startedAt = System.nanoTime();
        String collectionName = store.collectionDoc(root, collection).name();
        EnvironmentRef environment = environment(root, collection, options.environment());

        // Held for the length of the run; capture will write into it. Empty until then.
        Map<String, String> runtime = new HashMap<>();
        List<CollectionNode> nodes = store.requestNodes(root, collection);
        List<RequestResult> results = new ArrayList<>();

        for (int index = 0; index < nodes.size(); index++) {
            // Recomputed per request so a value captured by an earlier one is visible.
            Map<String, String> variables = new HashMap<>(Variables.forCollection(
                    store, root, collection, environment == null ? null : environment.path(),
                    runtime));
            if (options.variables() != null) {
                variables.putAll(options.variables());
            }

            RequestResult result = redact(
                    runOne(root, nodes.get(index), variables), options.variables());
            results.add(result);
            if (listener != null) {
                listener.onRequest(index, nodes.size(), result);
            }
        }

        int passed = (int) results.stream().filter(RequestResult::passed).count();
        int errored = (int) results.stream().filter(RequestResult::errored).count();
        return new RunResult(
                collectionName,
                environment == null ? null : environment.name(),
                (System.nanoTime() - startedAt) / 1_000_000,
                results.size(),
                passed,
                results.size() - passed - errored,
                errored,
                results);
    }

    private RequestResult runOne(Path root, CollectionNode node, Map<String, String> variables) {
        String url = null;
        String method = node.method();
        try {
            StoredRequest stored = store.read(root, node.path());
            RequestSpec spec = stored.toSpec();
            url = spec.url();
            method = spec.methodOrDefault();

            ResponseData response = engine.send(spec, variables);
            List<AssertionResult> assertions = response.assertions();
            boolean passed = assertions.stream().allMatch(AssertionResult::passed);
            return new RequestResult(node.path(), node.name(), method, url,
                    response.status(), response.timing().totalMs(), null, passed, assertions);
        } catch (RpcException e) {
            return error(node, method, url, e.getMessage());
        } catch (RuntimeException e) {
            // A bug in one request must not take the whole run with it.
            String detail = e.getMessage();
            return error(node, method, url,
                    detail == null ? e.getClass().getSimpleName() : detail);
        }
    }

    /** Values shorter than this are not masked: replacing "1" everywhere would garble a report. */
    static final int MIN_REDACTED_LENGTH = 4;

    /**
     * Masks the explicitly supplied variables (the CLI's secrets and {@code --var}s) wherever
     * they resurface. A result echoes resolved assertion values and response text, and a CI
     * report is far more widely read than the terminal the run happened in.
     */
    static RequestResult redact(RequestResult result, Map<String, String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return result;
        }
        List<String> values = secrets.values().stream()
                .filter(v -> v != null && v.length() >= MIN_REDACTED_LENGTH)
                .toList();
        if (values.isEmpty()) {
            return result;
        }
        List<AssertionResult> assertions = result.assertions().stream()
                .map(a -> new AssertionResult(a.type(), mask(a.target(), values), a.op(),
                        mask(a.expected(), values), a.passed(), mask(a.actual(), values),
                        mask(a.message(), values)))
                .toList();
        return new RequestResult(result.path(), result.name(), result.method(), result.url(),
                result.status(), result.durationMs(), mask(result.error(), values),
                result.passed(), assertions);
    }

    private static String mask(String text, List<String> secrets) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String secret : secrets) {
            masked = masked.replace(secret, "***");
        }
        return masked;
    }

    private static RequestResult error(CollectionNode node, String method, String url, String message) {
        return new RequestResult(node.path(), node.name(), method, url,
                null, null, message, false, List.of());
    }

    /**
     * Matches by display name (case-insensitive), by file basename, or by exact relative path,
     * so {@code -e prod} works and the RPC's path form does too.
     */
    private EnvironmentRef environment(Path root, String collection, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        List<EnvironmentRef> available = store.environmentNames(root, collection);
        String key = wanted.trim().toLowerCase(Locale.ROOT);
        for (EnvironmentRef ref : available) {
            if (ref.path().equals(wanted.trim())
                    || ref.name().toLowerCase(Locale.ROOT).equals(key)
                    || baseName(ref.path()).toLowerCase(Locale.ROOT).equals(key)) {
                return ref;
            }
        }
        String names = available.isEmpty()
                ? "none"
                : String.join(", ", available.stream().map(EnvironmentRef::name).toList());
        throw RpcException.invalidParams(
                "Unknown environment \"" + wanted + "\". Available: " + names);
    }

    private static String baseName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
