package dev.ping.imports;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ping.http.RequestSpec;
import dev.ping.store.CollectionDoc;
import dev.ping.store.EnvironmentDoc;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes imported collections through {@link YamlStore}, so the store's path rules apply and
 * nothing here touches the filesystem directly.
 *
 * <p>Every import creates <em>new</em> folders; an existing collection is never merged into or
 * overwritten. Before a request is written, credentials are lifted out of it: a literal token,
 * password or credential-looking header value becomes a {@code {{name}}} reference and the
 * value is returned in {@code secrets} for the shell to store. That is what keeps the "secrets
 * never touch collection files" rule true for a format that keeps them inline.
 */
public final class CollectionWriter {

    /** @param path relative to the workspace root */
    public record Written(String path, String name, int requests, int environments) {
    }

    /** A credential lifted out of a file; {@code name} is what the file now references. */
    public record Secret(String name, String value) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Output(List<Written> collections, List<String> warnings, List<Secret> secrets) {
    }

    private final YamlStore store;
    private final List<Secret> secrets = new ArrayList<>();
    private final Set<String> secretNames = new HashSet<>();

    public CollectionWriter(YamlStore store) {
        this.store = store;
    }

    public Output write(Path root, CollectionImporter.Parsed parsed) {
        List<Written> written = new ArrayList<>();
        for (ImportedCollection collection : parsed.collections()) {
            written.add(writeCollection(root, collection));
        }
        return new Output(written, parsed.warnings(), secrets);
    }

    private Written writeCollection(Path root, ImportedCollection collection) {
        String path = store.newFolder(root, "", collection.name());
        try {
            store.saveCollection(root, path, new CollectionDoc(collection.name(), collection.variables(), collection.docs()));
            // The folder's own name, so a second import of the same collection ("Name 2") gets its
            // own secret names rather than overwriting the first import's.
            String slug = YamlStore.slugify(path, "collection");
            int requests = writeFolder(root, path, slug, collection.root());

            Set<String> usedNames = new HashSet<>();
            int environments = 0;
            for (ImportedCollection.Environment environment : collection.environments()) {
                String name = uniqueName(environment.name(), usedNames);
                store.saveEnvironment(root, path, null, new EnvironmentDoc(name, environment.variables()));
                environments++;
            }
            return new Written(path, collection.name(), requests, environments);
        } catch (RuntimeException e) {
            // Half a collection is worse than none: remove what this call created.
            try {
                store.delete(root, path);
            } catch (RuntimeException ignored) {
                // The original failure is the useful one.
            }
            throw e;
        }
    }

    private int writeFolder(Path root, String path, String slug, ImportedCollection.Folder folder) {
        int count = 0;
        for (StoredRequest request : folder.requests()) {
            store.writeUnique(root, path, "request", lift(request, slug));
            count++;
        }
        for (ImportedCollection.Folder child : folder.folders()) {
            String childPath = store.newFolder(root, path, child.name());
            count += writeFolder(root, childPath, slug, child);
        }
        return count;
    }

    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        for (int suffix = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); suffix++) {
            candidate = name + " " + suffix;
        }
        return candidate;
    }

    // --- secrets --------------------------------------------------------------------------

    private StoredRequest lift(StoredRequest request, String collectionSlug) {
        String prefix = "import-" + collectionSlug + "-" + YamlStore.slugify(request.name(), "request");

        RequestSpec.Auth auth = request.auth();
        if (auth != null) {
            auth = new RequestSpec.Auth(auth.type(), auth.username(),
                    liftValue(auth.password(), prefix, "password"),
                    liftValue(auth.token(), prefix, "token"),
                    auth.key(),
                    liftValue(auth.value(), prefix, "value"),
                    auth.in(), auth.tokenUrl(), auth.authUrl(), auth.clientId(),
                    liftValue(auth.clientSecret(), prefix, "client-secret"),
                    auth.scopes(), auth.redirectUri(), auth.accessToken(), auth.refreshToken(),
                    auth.expiresAtMillis());
        }

        RequestSpec.Body body = request.body();
        if (body != null && body.fields() != null) {
            body = new RequestSpec.Body(body.type(), body.content(), body.contentType(),
                    liftParams(body.fields(), prefix, "field"));
        }

        return new StoredRequest(request.name(), request.method(), request.url(),
                liftParams(request.query(), prefix, "query"),
                liftParams(request.headers(), prefix, "header"),
                body, auth, request.timeoutMs(), request.redirects(), request.verifyTls(),
                request.maxBodyBytes(), request.asserts(), request.capture(), request.docs(),
                request.cookies(), request.httpVersion());
    }

    /** Values of credential-looking names become references; everything else is untouched. */
    private List<RequestSpec.Param> liftParams(List<RequestSpec.Param> params, String prefix, String kind) {
        if (params == null) {
            return null;
        }
        List<RequestSpec.Param> lifted = new ArrayList<>();
        for (RequestSpec.Param param : params) {
            String value = param.value();
            if (param.name() != null && ImportSupport.SENSITIVE_NAME.matcher(param.name()).matches()) {
                value = liftValue(value, prefix, kind + "-" + YamlStore.slugify(param.name(), "value"));
            }
            lifted.add(new RequestSpec.Param(param.name(), value, param.enabled(),
                    param.file(), param.filename(), param.contentType()));
        }
        return lifted;
    }

    /** @return the reference that replaces {@code value}, or the value itself when it is not a literal secret */
    private String liftValue(String value, String prefix, String field) {
        if (value == null || value.isBlank() || value.contains("{{")) {
            return value;
        }
        String base = prefix + "-" + field;
        String name = base;
        for (int suffix = 2; !secretNames.add(name); suffix++) {
            name = base + "-" + suffix;
        }
        secrets.add(new Secret(name, value));
        return "{{" + name + "}}";
    }
}
