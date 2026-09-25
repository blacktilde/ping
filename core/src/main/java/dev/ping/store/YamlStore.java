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
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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
                    .filter(path -> !Files.isSymbolicLink(path))
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
                null, null, null, null, null, null, null, null, null, null);
        write(base, relative(base, file), request);
        return relative(base, file);
    }

    // --- rename, move, duplicate -----------------------------------------------------------

    /**
     * Renames a request or a folder and returns its new relative path.
     *
     * <p>A request keeps every field it has: only {@code name} changes, edited in the parsed
     * tree rather than through {@link StoredRequest}, so a field this core does not know yet
     * survives. The file is renamed to match unless it already carries that name's slug (or
     * the slug with a numeric suffix), so a rename never churns file names.
     *
     * <p>A folder that already exists under the new name is an error, not a silent
     * {@code Name 2}: the user typed that name. Renaming a collection also renames it in
     * {@code collection.yaml}.
     */
    public String rename(Path root, String relativePath, String name) {
        Path base = normalize(root);
        Path source = resolve(base, relativePath);
        guardReserved(base, source);
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw RpcException.invalidParams("A name is required");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw RpcException.storeFailed("No such path: " + relativePath);
        }
        return Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                ? renameFolder(base, source, clean)
                : renameRequest(base, source, clean);
    }

    private String renameRequest(Path base, Path file, String name) {
        com.fasterxml.jackson.databind.node.ObjectNode node = readTree(base, file);
        if (name.equals(node.path("name").asText(null))) {
            return relative(base, file);
        }
        node.put("name", name);
        writeValue(file, node);
        return followSlug(base, file, slugify(name, "request"));
    }

    /**
     * Renames a file to match its new display name's slug and returns its relative path. A file
     * that already carries the slug, or the slug with a numeric suffix, stays where it is.
     */
    private static String followSlug(Path base, Path file, String slug) {
        String stem = baseName(file);
        if (stem.equals(slug) || stem.matches(java.util.regex.Pattern.quote(slug) + "-\\d+")) {
            return relative(base, file);
        }
        Path target = unique(file.getParent(), slug);
        try {
            moveInPlace(file, target);
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not rename " + relative(base, file) + ": " + e.getMessage(), e);
        }
        return relative(base, target);
    }

    private String renameFolder(Path base, Path directory, String name) {
        String clean = folderName(name, "");
        if (clean.isEmpty()) {
            throw RpcException.invalidParams("That name has nothing usable for a folder name");
        }
        Path parent = directory.getParent();
        Path target = parent.resolve(clean).normalize();
        if (!target.startsWith(parent)) {
            throw RpcException.invalidParams("Folder name escapes its parent: " + name);
        }
        try {
            // Compared by the exact name, not with Path.equals: on Windows that ignores case, so
            // "auth" and "Auth" would read as the same path and a case-only rename would do nothing.
            if (!target.getFileName().toString().equals(directory.getFileName().toString())) {
                // On a case-insensitive filesystem the new name "exists" but is this same folder.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSameFile(target, directory)) {
                    throw RpcException.invalidParams("A folder named \"" + clean + "\" already exists here");
                }
                moveInPlace(directory, target);
            }
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not rename to " + clean + ": " + e.getMessage(), e);
        }
        String path = relative(base, target);
        if (isCollection(base, target) && Files.isRegularFile(target.resolve(COLLECTION_FILE))) {
            CollectionDoc doc = collectionDoc(base, path);
            saveCollection(base, path, new CollectionDoc(name, doc.variables(), doc.docs()));
        }
        return path;
    }

    /**
     * Moves a request or folder into an existing folder or collection and returns its new path.
     *
     * <p>A collection cannot move (it would leave its {@code collection.yaml} and environments
     * stranded inside another collection), nothing moves to the workspace root, and a folder
     * cannot move into itself. A request that would collide takes a unique file name; a folder
     * that would collide is an error. Nothing is ever replaced.
     */
    public String move(Path root, String relativePath, String toPath) {
        Path base = normalize(root);
        Path source = resolve(base, relativePath);
        guardReserved(base, source);
        if (toPath == null || toPath.isBlank()) {
            throw RpcException.invalidParams("A destination folder is required");
        }
        Path destination = resolve(base, toPath);
        if (!Files.isDirectory(destination)) {
            throw RpcException.storeFailed("No such folder: " + toPath);
        }
        if (relative(base, destination).equalsIgnoreCase(ENVIRONMENTS_DIR)
                || List.of(relative(base, destination).split("/")).stream()
                        .anyMatch(segment -> segment.equalsIgnoreCase(ENVIRONMENTS_DIR))) {
            throw RpcException.invalidParams("Requests cannot be moved into an environments folder");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw RpcException.storeFailed("No such path: " + relativePath);
        }

        boolean folder = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (folder && isCollection(base, source)) {
            throw RpcException.invalidParams("A collection cannot be moved into another one");
        }
        if (folder && destination.startsWith(source)) {
            throw RpcException.invalidParams("A folder cannot be moved into itself");
        }
        if (destination.equals(source.getParent())) {
            return relative(base, source);
        }

        Path target;
        if (folder) {
            target = destination.resolve(source.getFileName());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw RpcException.invalidParams("\"" + source.getFileName()
                        + "\" already exists in the destination");
            }
        } else {
            target = unique(destination, baseName(source));
        }
        try {
            moveInPlace(source, target);
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not move " + relativePath + ": " + e.getMessage(), e);
        }
        return relative(base, target);
    }

    /**
     * Copies a request or a whole folder next to the original as "<name> copy" and returns the
     * copy's relative path. Symlinks are skipped, as {@link #delete} does not follow them either.
     */
    public String duplicate(Path root, String relativePath) {
        Path base = normalize(root);
        Path source = resolve(base, relativePath);
        guardReserved(base, source);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw RpcException.storeFailed("No such path: " + relativePath);
        }
        try {
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                com.fasterxml.jackson.databind.node.ObjectNode node = readTree(base, source);
                String original = node.path("name").asText(baseName(source));
                String name = original + " copy";
                node.put("name", name);
                Path target = unique(source.getParent(), slugify(name, "request"));
                writeValue(target, node);
                return relative(base, target);
            }

            String name = source.getFileName() + " copy";
            String parent = relative(base, source.getParent());
            String copy = newFolder(base, source.getParent().equals(base) ? "" : parent, name);
            Path target = resolve(base, copy);
            copyTree(source, target);
            if (isCollection(base, target) && Files.isRegularFile(target.resolve(COLLECTION_FILE))) {
                CollectionDoc doc = collectionDoc(base, copy);
                saveCollection(base, copy, new CollectionDoc(doc.name() + " copy", doc.variables(), doc.docs()));
            }
            return copy;
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not duplicate " + relativePath + ": " + e.getMessage(), e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                    Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(directory).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                // walkFileTree does not follow links, so a symlink arrives here and is left behind.
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()));
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /** {@code collection.yaml} and anything under {@code environments/} are not tree items. */
    private static void guardReserved(Path base, Path path) {
        if (path.getFileName().toString().equals(COLLECTION_FILE)) {
            throw RpcException.invalidParams("collection.yaml is managed with the collection's variables");
        }
        for (String segment : relative(base, path).split("/")) {
            if (segment.equalsIgnoreCase(ENVIRONMENTS_DIR)) {
                throw RpcException.invalidParams("Environments are managed from the variables panel");
            }
        }
    }

    /** A collection is a folder directly under the workspace root. */
    private static boolean isCollection(Path base, Path directory) {
        return Files.isDirectory(directory) && base.equals(directory.getParent());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode readTree(Path base, Path file) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = YAML.readTree(file.toFile());
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                return object;
            }
            throw RpcException.storeFailed(relative(base, file) + " is not a request file");
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not parse " + relative(base, file) + ": " + e.getMessage(), e);
        }
    }

    /** Renames within the same filesystem; the callers have already ruled out a clash. */
    private static void moveInPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /**
     * Creates a new, empty folder under {@code parentPath} and returns its relative path.
     *
     * <p>The name is sanitised for every platform's filesystem and made unique among its
     * siblings ({@code Name}, {@code Name 2}, ...), so an import can never overwrite a folder
     * that is already there. An empty parent means the workspace root.
     */
    public String newFolder(Path root, String parentPath, String name) {
        Path base = normalize(root);
        Path parent = parentPath == null || parentPath.isBlank() ? base : resolve(base, parentPath);
        if (!Files.isDirectory(parent)) {
            throw RpcException.storeFailed("No such folder: " + parentPath);
        }

        String clean = folderName(name, "Imported");
        try {
            for (int suffix = 1; ; suffix++) {
                Path candidate = parent.resolve(suffix == 1 ? clean : clean + " " + suffix).normalize();
                if (!candidate.startsWith(parent)) {
                    throw RpcException.invalidParams("Folder name escapes its parent: " + name);
                }
                try {
                    Files.createDirectory(candidate);
                    return relative(base, candidate);
                } catch (java.nio.file.FileAlreadyExistsException taken) {
                    // Try the next suffix; createDirectory is what makes this race-free.
                }
            }
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not create a folder for " + name, e);
        }
    }

    /**
     * Writes a request into an existing folder under a unique file name derived from its name.
     *
     * @param fallbackSlug used when the name has nothing usable for a file name
     * @return the relative path of the file that was written
     */
    public String writeUnique(Path root, String directoryPath, String fallbackSlug, StoredRequest request) {
        Path base = normalize(root);
        Path directory = resolve(base, directoryPath);
        if (!Files.isDirectory(directory)) {
            throw RpcException.storeFailed("No such folder: " + directoryPath);
        }
        Path file = unique(directory, slugify(request.name(), fallbackSlug));
        write(base, relative(base, file), request);
        return relative(base, file);
    }

    /**
     * A folder name that is safe on Linux, macOS and Windows: no separators, reserved
     * characters, control characters, leading or trailing dots and spaces, or device names,
     * and never {@code environments}, which the tree treats as reserved at every level.
     */
    public static String folderName(String name, String fallback) {
        String clean = representable(name == null ? "" : name)
                .replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]+", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[. ]+|[. ]+$", "");
        if (clean.length() > 100) {
            clean = clean.substring(0, 100).replaceAll("[. ]+$", "");
        }
        if (clean.isEmpty()) {
            return fallback;
        }
        if (clean.equalsIgnoreCase(ENVIRONMENTS_DIR)) {
            return clean + " folder";
        }
        if (clean.toLowerCase(Locale.ROOT).matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
            return clean + "_";
        }
        return clean;
    }

    /**
     * Replaces whatever this JVM cannot put in a path with a space, the same as a reserved
     * character.
     *
     * <p>A file name reaches the OS encoded as {@code sun.jnu.encoding}, which follows the
     * locale rather than moving to UTF-8 with {@code file.encoding}. Under a non-UTF-8 locale
     * a name like {@code 日本語} is not a folder name at all: {@code Path.of} throws
     * {@link InvalidPathException} rather than resolving. Asking it is the only honest test,
     * since the encoding it uses is not something this can read.
     */
    private static String representable(String name) {
        try {
            Path.of(name);
            return name;
        } catch (InvalidPathException unusable) {
            StringBuilder usable = new StringBuilder(name.length());
            name.codePoints().forEach(codePoint -> {
                String character = new String(Character.toChars(codePoint));
                try {
                    Path.of(character);
                    usable.append(character);
                } catch (InvalidPathException unusableCharacter) {
                    usable.append(' ');
                }
            });
            return usable.toString();
        }
    }

    /**
     * Creates a starter collection, so the first run is not an empty sidebar.
     *
     * <p>Idempotent: an existing collection directory, metadata file or request is left
     * alone, so running it again never overwrites anything the user wrote.
     *
     * @return the collection path relative to the workspace root
     */
    public String scaffold(Path root, String collectionName) {
        Path base = root.toAbsolutePath().normalize();
        String name = collectionName == null || collectionName.isBlank()
                ? "My Collection"
                : collectionName.trim();
        Path collection = base.resolve(name);

        try {
            Files.createDirectories(collection);

            Path metadata = collection.resolve(COLLECTION_FILE);
            if (!Files.exists(metadata)) {
                writeValue(metadata, new CollectionDoc(name, List.of(), null));
            }

            boolean hasRequest;
            try (Stream<Path> entries = Files.list(collection)) {
                hasRequest = entries.anyMatch(entry -> isYaml(entry)
                        && !entry.getFileName().toString().equals(COLLECTION_FILE));
            }
            if (!hasRequest) {
                StoredRequest starter = new StoredRequest("Get started", "GET",
                        "https://jsonplaceholder.typicode.com/todos/1",
                        List.of(), List.of(), new RequestSpec.Body("none", null, null, null),
                        null, null, null, null, null, null, null, null, null, null);
                writeValue(collection.resolve("get-started.yaml"), starter);
            }
            return relative(base, collection);
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not create the starter collection: " + e.getMessage(), e);
        }
    }

    /**
     * Every request in a collection, flattened in sidebar order: folders before requests,
     * each group by display name, depth first. The runner uses this so a run and the tree
     * cannot disagree about order.
     */
    public List<CollectionNode> requestNodes(Path root, String collectionPath) {
        Path base = normalize(root);
        Path directory = resolve(base, collectionPath);
        if (!Files.isDirectory(directory)) {
            throw RpcException.storeFailed("No such collection: " + collectionPath);
        }
        List<CollectionNode> flat = new ArrayList<>();
        flatten(children(base, directory), flat);
        return flat;
    }

    private static void flatten(List<CollectionNode> nodes, List<CollectionNode> into) {
        for (CollectionNode node : nodes) {
            if (CollectionNode.REQUEST.equals(node.type())) {
                into.add(node);
            } else {
                flatten(node.children(), into);
            }
        }
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
            return new CollectionDoc(directory.getFileName().toString(), List.of(), null);
        }
        try {
            CollectionDoc doc = YAML.readValue(file.toFile(), CollectionDoc.class);
            String name = doc.name() == null || doc.name().isBlank()
                    ? directory.getFileName().toString()
                    : doc.name();
            return new CollectionDoc(name, doc.variables(), doc.docs());
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
                    .filter(path -> !Files.isSymbolicLink(path))
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

    /**
     * Renames an environment and returns its new relative path.
     *
     * <p>Like a request rename, only {@code name} changes in the file, and the file follows the
     * new name's slug unless it already carries it. A name another environment of the same
     * collection already shows is an error: the picker lists environments by name, so two of
     * them would be indistinguishable.
     */
    public String renameEnvironment(Path root, String relativePath, String name) {
        Path base = normalize(root);
        Path file = environmentFile(base, relativePath);
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw RpcException.invalidParams("A name is required");
        }
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            boolean taken = siblings
                    .filter(YamlStore::isYaml)
                    .filter(path -> !hidden(path) && !Files.isSymbolicLink(path))
                    .filter(path -> !path.equals(file))
                    .anyMatch(path -> environmentName(path).equalsIgnoreCase(clean));
            if (taken) {
                throw RpcException.invalidParams("An environment named \"" + clean + "\" already exists");
            }
        } catch (IOException e) {
            throw RpcException.storeFailed("Could not read " + relative(base, file.getParent()), e);
        }

        com.fasterxml.jackson.databind.node.ObjectNode node = readTree(base, file);
        if (!clean.equals(node.path("name").asText(null))) {
            node.put("name", clean);
            writeValue(file, node);
        }
        return followSlug(base, file, slugify(clean));
    }

    /** Deletes one environment file. */
    public void deleteEnvironment(Path root, String relativePath) {
        Path file = environmentFile(normalize(root), relativePath);
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not delete " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a path that must name an environment: a YAML file directly inside an
     * {@code environments/} folder. Renaming and deleting go through here, so neither can be
     * pointed at a request or a collection's own file.
     */
    private static Path environmentFile(Path base, String relativePath) {
        Path file = resolve(base, relativePath);
        Path parent = file.getParent();
        if (!isYaml(file) || parent == null || parent.equals(base)
                || !parent.getFileName().toString().equals(ENVIRONMENTS_DIR)) {
            throw RpcException.invalidParams("Not an environment: " + relativePath);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw RpcException.storeFailed("No such environment: " + relativePath);
        }
        return file;
    }

    /**
     * Deletes a request file, or a collection or folder and everything under it.
     *
     * <p>Symlinks are removed as links and never followed, so a link cannot make this reach
     * outside the workspace; the root itself is rejected by {@link #resolve}.
     */
    public void delete(Path root, String relativePath) {
        Path target = resolve(root, relativePath);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw RpcException.storeFailed("No such path: " + relativePath);
        }

        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.walk(target)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not delete " + relativePath + ": " + e.getMessage(), e);
        }
    }

    // --- tree building -------------------------------------------------------------------

    private List<CollectionNode> children(Path root, Path directory) {
        List<CollectionNode> folders = new ArrayList<>();
        List<CollectionNode> requests = new ArrayList<>();

        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.sorted(byName()).toList()) {
                if (hidden(entry) || Files.isSymbolicLink(entry)) {
                    // Symlinks are neither shown nor followed: one can point outside the
                    // workspace, and a self-referential one fills the tree with copies.
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

        // Folders before requests reads like a file tree. Within each group the order is
        // alphabetical by the name shown, not the file name: a request's display name comes
        // from its YAML and can differ from its slug, so sorting by path would look random.
        folders.sort(byDisplayName());
        requests.sort(byDisplayName());
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
        // A lexical check is not enough: a symlink inside the folder can point outside it, so
        // the real path of the deepest existing ancestor has to stay under the real root too.
        if (!realPath(resolved).startsWith(base)) {
            throw RpcException.invalidParams("Path escapes the workspace: " + relativePath);
        }
        return resolved;
    }

    private static Path normalize(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /**
     * Resolves symlinks on the deepest existing ancestor and re-attaches the rest, so a path
     * that does not exist yet can still be checked for escape.
     */
    private static Path realPath(Path path) {
        Path existing = path;
        List<Path> missing = new ArrayList<>();
        while (existing != null && !Files.exists(existing)) {
            missing.add(0, existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return path.toAbsolutePath().normalize();
        }

        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
        for (Path part : missing) {
            real = real.resolve(part);
        }
        return real;
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
            makeShareable(file);
        } catch (IOException e) {
            throw RpcException.storeFailed(
                    "Could not write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Collections are meant to be committed and shared, so a file Ping wrote should look like
     * one a person wrote. {@code createTempFile} is deliberately owner-only, and the rename
     * carries that, so set a readable mode explicitly where the filesystem has modes.
     */
    private static void makeShareable(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // A filesystem without modes is not a reason to fail the write.
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
        return slugify(name, "environment");
    }

    /** Lowercase, dash-separated file stem for a display name; the fallback covers names with nothing ASCII in them. */
    public static String slugify(String name, String fallback) {
        String slug = (name == null ? "" : name)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? fallback : slug;
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

    private static Comparator<CollectionNode> byDisplayName() {
        return Comparator.comparing(node -> node.name().toLowerCase(Locale.ROOT));
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
