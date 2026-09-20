package dev.ping.imports;

import dev.ping.http.RequestSpec;
import dev.ping.store.CollectionNode;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionWriterTest {

    private static final String POSTMAN = """
            {"info": {"name": "Petstore", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "variable": [{"key": "baseUrl", "value": "https://p.io"}],
             "item": [
               {"name": "Pets", "item": [
                 {"name": "List pets", "request": {"method": "GET", "url": "{{baseUrl}}/pets"}},
                 {"name": "List pets", "request": {"method": "GET", "url": "{{baseUrl}}/pets?again=1"}}]},
               {"name": "Login", "event": [{"listen": "test", "script": {"exec": ["pm.test('x')"]}}],
                "request": {"method": "POST", "url": "{{baseUrl}}/login?api_key=qkey123",
                  "header": [{"key": "X-Api-Key", "value": "hkey456"}, {"key": "Cookie", "value": "sid=ckie789"},
                             {"key": "Accept", "value": "application/json"}, {"key": "X-Ref", "value": "{{already}}"}],
                  "auth": {"type": "basic", "basic": [{"key": "username", "value": "alice"}, {"key": "password", "value": "pw000"}]},
                  "body": {"mode": "urlencoded", "urlencoded": [{"key": "user", "value": "alice"}, {"key": "password", "value": "bodypw1"}]}}},
               {"name": "Tokened", "request": {"method": "GET", "url": "{{baseUrl}}/t",
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "bearer-literal"}]}}},
               {"name": "Keyed", "request": {"method": "GET", "url": "{{baseUrl}}/k",
                  "auth": {"type": "apikey", "apikey": [{"key": "key", "value": "X-Key"}, {"key": "value", "value": "apikey-literal"}]}}},
               {"name": "Referenced", "request": {"method": "GET", "url": "{{baseUrl}}/r",
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{myToken}}"}]}}}
             ]}""";

    private static final String INSOMNIA = """
            {"_type": "export", "__export_format": 4, "resources": [
              {"_id": "w", "_type": "workspace", "name": "Shop"},
              {"_id": "b", "_type": "environment", "parentId": "w", "name": "Base", "data": {"host": "s.io"}},
              {"_id": "e1", "_type": "environment", "parentId": "b", "name": "Prod", "data": {"host": "prod.s.io"}},
              {"_id": "e2", "_type": "environment", "parentId": "b", "name": "prod", "data": {"host": "other.s.io"}},
              {"_id": "r", "_type": "request", "parentId": "w", "name": "Home", "method": "GET", "url": "https://s.io"}]}""";

    @TempDir
    Path root;

    private final YamlStore store = new YamlStore();

    private CollectionWriter.Output write(String content) {
        return new CollectionWriter(store).write(root, CollectionImporter.parse(content));
    }

    private static Set<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    void createsTheCollectionFoldersAndRequestsOnDisk() throws IOException {
        CollectionWriter.Output output = write(POSTMAN);

        assertEquals(1, output.collections().size());
        CollectionWriter.Written written = output.collections().get(0);
        assertEquals("Petstore", written.path());
        assertEquals(6, written.requests());
        assertEquals(Set.of("collection.yaml", "Pets", "login.yaml", "tokened.yaml", "keyed.yaml",
                "referenced.yaml"), names(root.resolve("Petstore")));
        assertEquals(Set.of("list-pets.yaml", "list-pets-2.yaml"), names(root.resolve("Petstore/Pets")));
        assertTrue(Files.readString(root.resolve("Petstore/collection.yaml")).contains("baseUrl"));
    }

    @Test
    void aSecondImportGetsItsOwnFolderAndNeverTouchesTheFirst() throws IOException {
        write(POSTMAN);
        Path first = root.resolve("Petstore/login.yaml");
        String before = Files.readString(first);

        CollectionWriter.Output second = write(POSTMAN);

        assertEquals("Petstore 2", second.collections().get(0).path());
        assertEquals(before, Files.readString(first));
        assertTrue(Files.isRegularFile(root.resolve("Petstore 2/login.yaml")));
        assertTrue(second.secrets().stream().allMatch(s -> s.name().startsWith("import-petstore-2-")),
                "a second import must not reuse (and overwrite) the first import's secret names");
    }

    @Test
    void environmentsAreWrittenAndDuplicateNamesAreKeptApart() throws IOException {
        CollectionWriter.Output output = write(INSOMNIA);
        assertEquals(2, output.collections().get(0).environments());
        assertEquals(Set.of("prod.yaml", "prod-2.yaml"), names(root.resolve("Shop/environments")));
    }

    @Test
    void noLiteralCredentialEverReachesAFile() throws IOException {
        CollectionWriter.Output output = write(POSTMAN);

        String everything;
        try (Stream<Path> files = Files.walk(root)) {
            everything = files.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }).collect(Collectors.joining("\n"));
        }
        for (String literal : List.of("pw000", "bodypw1", "qkey123", "hkey456", "ckie789",
                "bearer-literal", "apikey-literal")) {
            assertFalse(everything.contains(literal), literal + " leaked into a file");
            assertTrue(output.secrets().stream().anyMatch(s -> s.value().contains(literal)),
                    literal + " should have been returned as a secret");
        }
        // Values that are not secrets, or already references, are left exactly as they were.
        assertTrue(everything.contains("alice"));
        assertTrue(everything.contains("application/json"));
        assertTrue(everything.contains("{{already}}"));
        assertTrue(everything.contains("{{myToken}}"));
        assertTrue(output.secrets().stream().noneMatch(s -> s.value().contains("{{")));
        assertEquals(7, output.secrets().size());
    }

    @Test
    void everyReturnedSecretIsReferencedByExactlyOneFileValue() throws IOException {
        CollectionWriter.Output output = write(POSTMAN);
        String login = Files.readString(root.resolve("Petstore/login.yaml"));
        assertTrue(login.contains("{{import-petstore-login-password}}"), login);
        assertTrue(login.contains("{{import-petstore-login-header-x-api-key}}"), login);
        assertEquals(output.secrets().size(),
                output.secrets().stream().map(CollectionWriter.Secret::name).distinct().count());
    }

    @Test
    void importedRequestsReadBackWithTheirNotes() {
        write(POSTMAN);
        StoredRequest login = store.read(root, "Petstore/login.yaml");
        assertEquals("POST", login.method());
        assertEquals("{{baseUrl}}/login", login.url());
        assertTrue(login.docs().contains("### Test script"), login.docs());
        assertEquals("form", login.body().type());

        List<CollectionNode> tree = store.scan(root);
        assertEquals("Petstore", tree.get(0).name());
        assertEquals(List.of("Pets"), tree.get(0).children().stream()
                .filter(n -> CollectionNode.FOLDER.equals(n.type())).map(CollectionNode::name).toList());
    }

    @Test
    void hostileNamesStayInsideTheWorkspace() throws IOException {
        String[] names = {"../evil", "a/b\\c", "CON", "", "   ", "..", "environments", "日本語", "x:y*z?"};
        for (String name : names) {
            write("""
                    {"info": {"name": %s, "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                     "item": [{"name": %s, "item": [{"name": %s, "request": {"method": "GET", "url": "https://x.io"}}]}]}
                    """.formatted(quote(name), quote(name), quote(name)));
        }

        Path outside = root.getParent();
        assertFalse(Files.exists(outside.resolve("evil")), "a name must never escape the root");
        try (Stream<Path> walk = Files.walk(root)) {
            assertTrue(walk.allMatch(p -> p.normalize().startsWith(root)));
        }
        for (String entry : names(root)) {
            assertFalse(entry.startsWith("."), entry);
            assertFalse(entry.matches(".*[/\\\\:*?\"<>|].*"), entry);
            assertFalse(entry.equalsIgnoreCase("con"), entry);
        }
        assertEquals(names.length, names(root).size(), "each import got its own folder: " + names(root));
        assertTrue(names(root).contains("Imported"), "an empty name falls back to a usable one");
        assertTrue(names(root).contains("environments folder"), "the reserved name is not used as-is");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void requestNamesWithNothingUsableForAFileNameStillGetAFile() throws IOException {
        write("""
                {"info": {"name": "Emoji", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": [{"name": "日本語", "request": {"method": "GET", "url": "https://x.io"}},
                          {"name": "!!!", "request": {"method": "GET", "url": "https://x.io"}}]}""");
        assertEquals(Set.of("collection.yaml", "request.yaml", "request-2.yaml"), names(root.resolve("Emoji")));
    }

    @Test
    void aRequestNamedCollectionDoesNotClobberTheMetadataFile() throws IOException {
        write("""
                {"info": {"name": "Meta", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "variable": [{"key": "v", "value": "1"}],
                 "item": [{"name": "collection", "request": {"method": "GET", "url": "https://x.io"}}]}""");
        assertEquals(Set.of("collection.yaml", "collection-2.yaml"), names(root.resolve("Meta")));
        assertTrue(Files.readString(root.resolve("Meta/collection.yaml")).contains("variables"));
    }

    @Test
    void theSourcesDescriptionIsWrittenAsTheCollectionsNotes() throws IOException {
        write("""
                {"info": {"name": "Noted", "description": "Read me first", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": []}""");
        assertEquals("Read me first", store.collectionDoc(root, "Noted").docs());
        assertTrue(Files.readString(root.resolve("Noted/collection.yaml")).contains("docs: Read me first"));
    }

    @Test
    void theStoreHelpersAreDeterministic() {
        assertEquals("a b", YamlStore.folderName("a/b", "x").replaceAll("\\s+", " "));
        assertEquals("x", YamlStore.folderName("...", "x"));
        assertEquals("PRN_", YamlStore.folderName("PRN", "x"));
        assertEquals("request", YamlStore.slugify("日本語", "request"));
        assertEquals(RequestSpec.Param.class, new RequestSpec.Param("a", "b", true).getClass());
    }
}
