package dev.ping.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The collection tree on disk: a folder per collection, a YAML file per request.
 *
 * <p>This lives in the core rather than the shell so the eventual CLI runner can read the
 * same collections with no Electron involved. The Electron main process still owns the
 * workspace root, file watching and dialogs; it hands this the root and a relative path.
 *
 * <p>Every path is resolved against the workspace root and rejected if it escapes, so a
 * renderer bug cannot reach outside the folder the user opened.
 *
 * <p>Two names inside a collection are reserved and never appear in the sidebar:
 * {@code collection.yaml} holds the collection's own metadata, and {@code environments/}
 * holds one file per environment.
 */
public final class YamlStore {

    /** Collection metadata: name and collection-level variables. */
    public static final String COLLECTION_FILE = "collection.yaml";

    /** Folder of environment files, each overriding collection variables of the same name. */
    public static final String ENVIRONMENTS_DIR = "environments";

    /**
     * Loads as well as saves, so the writer's output is exactly what the reader expects.
     * Unknown properties are ignored: a file hand-edited with a field this core does not
     * yet understand still opens, rather than failing the whole collection.
     */
    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    /** Top-level folders, each a collection. Loose YAML at the root is not a collection. */
    public List<CollectionNode> scan(Path root) {
        Path base = normalize(root);
        if (!Files.isDirectory(base)) {
            return List.of();
        }

        List<CollectionNode> collections = new ArrayList<>();
        try (Stream<Path> entries = Files.list(base)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> !hidden(path))
                    .sorted(byName())
                    .forEach(path -> collections.add(new CollectionNode(
                            path.getFileName().toString(),
                            relative(base, path),
                            CollectionNode.COLLECTION,
                            null,
                            children(base, path))));
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not read " + base, e);
        }
        return collections;
    }

    public StoredRequest read(Path root, String relativePath) {
        Path file = resolve(root, relativePath);
        if (!Files.isRegularFile(file)) {
            throw RpcException.storeFailed("No such request: " + relativePath);
        }
        try {
            return YAML.readValue(file.toFile(), StoredRequest.class);
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not parse " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /** Writes through a temp file and a rename, so a crash never leaves a half-written file. */
    public void write(Path root, String relativePath, StoredRequest request) {
        writeValue(resolve(root, relativePath), request);
    }

    /**
     * Creates a request named {@code name} in an existing collection folder.
     *
     * @param collectionPath relative path of the collection; empty means the workspace root
     * @return the relative path of the file that was written
     */
    public String create(Path root, String collectionPath, String name) {
        Path base = normalize(root);
        Path directory = collectionPath == null || collectionPath.isBlank()
                ? base
                : resolve(base, collectionPath);
        if (!Files.isDirectory(directory)) {
            throw RpcException.storeFailed("No such collection: " + collectionPath);
        }

        Path file = unique(directory, slugify(name));
        StoredRequest request = new StoredRequest(name, "GET", "",
                List.of(), List.of(), new RequestSpec.Body("none", null, null, null),
                null, null, null, null, null);
        write(base, relative(base, file), request);
        return relative(base, file);
    }

    // --- collection metadata and environments ---------------------------------------------

    /** The collection's name and variables, defaulting to the folder name with none. */
    public CollectionDoc collectionDoc(Path root, String collectionPath) {
        Path base = normalize(root);
        Path directory = resolve(base, collectionPath);
        if (!Files.isDirectory(directory)) {
            throw RpcException.storeFailed("No such collection: " + collectionPath);
        }

        Path file = directory.resolve(COLLECTION_FILE);
        if (!Files.isRegularFile(file)) {
            return new CollectionDoc(directory.getFileName().toString(), List.of());
        }
        try {
            CollectionDoc doc = YAML.readValue(file.toFile(), CollectionDoc.class);
            String name = doc.name() == null || doc.name().isBlank()
                    ? directory.getFileName().toString()
                    : doc.name();
            return new CollectionDoc(name, doc.variables());
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not parse " + relative(base, file) + ": " + e.getMessage(), e);
        }
    }

    public void saveCollection(Path root, String collectionPath, CollectionDoc doc) {
        Path base = normalize(root);
        Path directory = resolve(base, collectionPath);
        if (!Files.isDirectory(directory)) {
            throw RpcException.storeFailed("No such collection: " + collectionPath);
        }
        writeValue(directory.resolve(COLLECTION_FILE), doc);
    }

    public List<EnvironmentRef> environmentNames(Path root, String collectionPath) {
        Path base = normalize(root);
        Path directory = collectionPath == null || collectionPath.isBlank()
                ? base
                : resolve(base, collectionPath);
        Path environments = directory.resolve(ENVIRONMENTS_DIR);
        if (!Files.isDirectory(environments)) {
            return List.of();
        }

        List<EnvironmentRef> refs = new ArrayList<>();
        try (Stream<Path> entries = Files.list(environments)) {
            entries.filter(YamlStore::isYaml)
                    .filter(path -> !hidden(path))
                    .sorted(byName())
                    .forEach(path -> refs.add(new EnvironmentRef(environmentName(path), relative(base, path))));
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not read " + environments, e);
        }
        return refs;
    }

    public EnvironmentDoc readEnvironment(Path root, String relativePath) {
        Path file = resolve(root, relativePath);
        if (!Files.isRegularFile(file)) {
            throw RpcException.storeFailed("No such environment: " + relativePath);
        }
        try {
            EnvironmentDoc doc = YAML.readValue(file.toFile(), EnvironmentDoc.class);
            String name = doc.name() == null || doc.name().isBlank()
                    ? baseName(file)
                    : doc.name();
            return new EnvironmentDoc(name, doc.variables());
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not parse " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes an environment file. With no path it lands at
     * {@code <collection>/environments/<slug>.yaml}, so creating one is a single call.
     *
     * @return the relative path of the file that was written
     */
    public String saveEnvironment(Path root, String collectionPath, String path, EnvironmentDoc doc) {
        Path base = normalize(root);
        Path file;
        if (path == null || path.isBlank()) {
            Path directory = resolve(base, collectionPath);
            if (!Files.isDirectory(directory)) {
                throw RpcException.storeFailed("No such collection: " + collectionPath);
            }
            file = directory.resolve(ENVIRONMENTS_DIR).resolve(slugify(doc.name()) + ".yaml");
        } else {
            file = resolve(base, path);
        }
        writeValue(file, doc);
        return relative(base, file);
    }

    // --- tree building -------------------------------------------------------------------

    private List<CollectionNode> children(Path root, Path directory) {
        List<CollectionNode> folders = new ArrayList<>();
        List<CollectionNode> requests = new ArrayList<>();

        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.sorted(byName()).toList()) {
                if (hidden(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (name.equals(ENVIRONMENTS_DIR)) {
                        continue;
                    }
                    folders.add(new CollectionNode(name, relative(root, entry),
                            CollectionNode.FOLDER, null, children(root, entry)));
                } else if (isYaml(entry) && !name.equals(COLLECTION_FILE)) {
                    requests.add(requestNode(root, entry));
                }
            }
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not read " + directory, e);
        }

        // Folders before requests reads like a file tree.
        folders.addAll(requests);
        return folders;
    }

    private CollectionNode requestNode(Path root, Path file) {
        String path = relative(root, file);
        try {
            StoredRequest request = YAML.readValue(file.toFile(), StoredRequest.class);
            String name = request.name() == null || request.name().isBlank()
                    ? baseName(file)
                    : request.name();
            return new CollectionNode(name, path, CollectionNode.REQUEST, request.method(), List.of());
        } catch (Exception e) {
            // A file the UI cannot parse still belongs in the tree; opening it reports the error.
            return new CollectionNode(baseName(file), path, CollectionNode.REQUEST, null, List.of());
        }
    }

    private static String environmentName(Path file) {
        try {
            EnvironmentDoc doc = YAML.readValue(file.toFile(), EnvironmentDoc.class);
            if (doc.name() != null && !doc.name().isBlank()) {
                return doc.name();
            }
        } catch (Exception e) {
            // An unreadable environment still appears, named after its file.
        }
        return baseName(file);
    }

    // --- paths ---------------------------------------------------------------------------

    private static Path resolve(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw RpcException.invalidParams("A path is required");
        }
        Path base = normalize(root);
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw RpcException.invalidParams("Path escapes the workspace: " + relativePath);
        }
        return resolved;
    }

    private static Path normalize(Path root) {
        return root.toAbsolutePath().normalize();
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static void writeValue(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            String yaml = YAML.writeValueAsString(value);
            Path temp = Files.createTempFile(file.getParent(), ".ping-", ".yaml");
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            move(temp, file);
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static Path unique(Path directory, String slug) {
        Path candidate = directory.resolve(slug + ".yaml");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(slug + "-" + suffix + ".yaml");
            suffix++;
        }
        return candidate;
    }

    private static String slugify(String name) {
        String slug = (name == null ? "" : name)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? "environment" : slug;
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Comparator<Path> byName() {
        return Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static boolean hidden(Path path) {
        return path.getFileName().toString().startsWith(".");
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
