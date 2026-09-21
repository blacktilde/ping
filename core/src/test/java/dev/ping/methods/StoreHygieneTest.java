package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * rename, move, duplicate and folder creation, driven as JSON like the shell drives them.
 * Every path a caller can name is checked: these are the calls that reshape a user's files.
 */
class StoreHygieneTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    private JsonNode call(String method, Map<String, Object> params) throws Exception {
        java.util.Map<String, Object> full = new java.util.LinkedHashMap<>(params);
        full.put("root", workspace.toString());
        String line = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", full));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(
                new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8)), out);
        StoreMethods.registerOn(server);
        server.serve();
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank())
                .map(l -> {
                    try {
                        return json.readTree(l);
                    } catch (Exception e) {
                        throw new AssertionError(l, e);
                    }
                })
                .filter(n -> n.has("id"))
                .findFirst().orElseThrow();
    }

    private String ok(String method, Map<String, Object> params) throws Exception {
        JsonNode response = call(method, params);
        assertFalse(response.has("error"), response.toString());
        return response.path("result").path("path").asText();
    }

    private int errorCode(String method, Map<String, Object> params) throws Exception {
        JsonNode response = call(method, params);
        assertTrue(response.has("error"), "expected an error: " + response);
        return response.path("error").path("code").asInt();
    }

    private void file(String relative, String content) throws IOException {
        Path path = workspace.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String read(String relative) throws IOException {
        return Files.readString(workspace.resolve(relative));
    }

    private boolean exists(String relative) {
        return Files.exists(workspace.resolve(relative));
    }

    private static Set<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static final int INVALID = RpcException.INVALID_PARAMS;

    // --- rename ---------------------------------------------------------------------------

    @Test
    void renamesARequestAndItsFileKeepingFieldsItDoesNotKnow() throws Exception {
        file("demo/get-user.yaml", "name: Get user\nmethod: GET\nurl: https://x.io\nx-custom: keep me\n");

        assertEquals("demo/fetch-a-user.yaml",
                ok("store.rename", Map.of("path", "demo/get-user.yaml", "name", "Fetch a user")));

        assertFalse(exists("demo/get-user.yaml"));
        String yaml = read("demo/fetch-a-user.yaml");
        assertTrue(yaml.contains("name: Fetch a user"), yaml);
        assertTrue(yaml.contains("x-custom: keep me"), "unknown fields must survive a rename: " + yaml);
        assertTrue(yaml.contains("url: https://x.io"), yaml);
    }

    @Test
    void aRenameKeepsTheFileWhenItsNameAlreadyMatchesTheSlug() throws Exception {
        file("demo/list.yaml", "name: Old\nmethod: GET\nurl: https://x.io\n");
        file("demo/list-2.yaml", "name: Other\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo/list.yaml", ok("store.rename", Map.of("path", "demo/list.yaml", "name", "List")));
        assertTrue(read("demo/list.yaml").contains("name: List"));
        assertEquals("demo/list-2.yaml", ok("store.rename", Map.of("path", "demo/list-2.yaml", "name", "List")),
                "a numeric suffix is left alone");
        assertEquals(Set.of("list.yaml", "list-2.yaml"), names(workspace.resolve("demo")));
    }

    @Test
    void aSlugCollisionGetsASuffixAndTheDisplayNameStaysAsTyped() throws Exception {
        file("demo/fetch.yaml", "name: Fetch\nmethod: GET\nurl: https://x.io\n");
        file("demo/other.yaml", "name: Other\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo/fetch-2.yaml", ok("store.rename", Map.of("path", "demo/other.yaml", "name", "Fetch")));
        assertTrue(read("demo/fetch-2.yaml").contains("name: Fetch"));
        assertTrue(read("demo/fetch.yaml").contains("name: Fetch"), "the original is untouched");
    }

    @Test
    void renamingARequestToItsOwnNameChangesNothing() throws Exception {
        file("demo/a.yaml", "name: A\nmethod: GET\nurl: https://x.io\n");
        String before = read("demo/a.yaml");
        assertEquals("demo/a.yaml", ok("store.rename", Map.of("path", "demo/a.yaml", "name", "  A  ")));
        assertEquals(before, read("demo/a.yaml"));
    }

    @Test
    void renamesAFolderAndRefusesANameThatIsTaken() throws Exception {
        file("demo/auth/login.yaml", "name: Login\nmethod: POST\nurl: https://x.io\n");
        file("demo/users/list.yaml", "name: List\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo/identity", ok("store.rename", Map.of("path", "demo/auth", "name", "identity")));
        assertTrue(exists("demo/identity/login.yaml"));
        assertFalse(exists("demo/auth"));

        assertEquals(INVALID, errorCode("store.rename", Map.of("path", "demo/identity", "name", "users")));
        assertTrue(exists("demo/identity/login.yaml"), "a refused rename leaves the folder where it was");
    }

    @Test
    void aFolderNameIsSanitisedAndCannotBeAReservedOne() throws Exception {
        file("demo/a/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        assertEquals("demo/environments folder",
                ok("store.rename", Map.of("path", "demo/a", "name", "environments")));
        assertEquals("demo/a b",
                ok("store.rename", Map.of("path", "demo/environments folder", "name", "a/b")));
        assertEquals(INVALID, errorCode("store.rename", Map.of("path", "demo/a b", "name", "...")));
    }

    @Test
    void aCaseOnlyFolderRenameIsAllowed() throws Exception {
        file("demo/auth/login.yaml", "name: Login\nmethod: GET\nurl: https://x.io\n");
        assertEquals("demo/Auth", ok("store.rename", Map.of("path", "demo/auth", "name", "Auth")));
        assertTrue(names(workspace.resolve("demo")).contains("Auth"));
    }

    @Test
    void renamingACollectionAlsoRenamesItInCollectionYaml() throws Exception {
        file("demo/collection.yaml", "name: Demo\nvariables:\n- name: base\n  value: https://x.io\n  enabled: true\n");
        file("demo/environments/dev.yaml", "name: Dev\nvariables: []\n");
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: '{{base}}'\n");

        assertEquals("Shop", ok("store.rename", Map.of("path", "demo", "name", "Shop")));
        String collection = read("Shop/collection.yaml");
        assertTrue(collection.contains("name: Shop"), collection);
        assertTrue(collection.contains("base"), "variables survive: " + collection);
        assertTrue(exists("Shop/environments/dev.yaml"));
        assertFalse(exists("demo"));
    }

    @Test
    void renamingAndDuplicatingACollectionKeepItsNotes() throws Exception {
        file("demo/collection.yaml", "name: Demo\ndocs: Notes about the demo\n");
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo copy", ok("store.duplicate", Map.of("path", "demo")));
        assertTrue(read("demo copy/collection.yaml").contains("docs: Notes about the demo"));

        assertEquals("Shop", ok("store.rename", Map.of("path", "demo", "name", "Shop")));
        assertTrue(read("Shop/collection.yaml").contains("docs: Notes about the demo"),
                "a rename rewrites collection.yaml and must not drop the notes");
    }

    @Test
    void reservedEntriesAndBlankNamesAreRefused() throws Exception {
        file("demo/collection.yaml", "name: Demo\n");
        file("demo/environments/dev.yaml", "name: Dev\n");
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");

        assertEquals(INVALID, errorCode("store.rename", Map.of("path", "demo/collection.yaml", "name", "x")));
        assertEquals(INVALID, errorCode("store.rename", Map.of("path", "demo/environments", "name", "x")));
        assertEquals(INVALID, errorCode("store.rename", Map.of("path", "demo/environments/dev.yaml", "name", "x")));
        assertEquals(INVALID, errorCode("store.rename", Map.of("path", "demo/get.yaml", "name", "   ")));
        assertEquals(RpcException.STORE_FAILED, errorCode("store.rename", Map.of("path", "demo/nope.yaml", "name", "x")));
    }

    // --- move -----------------------------------------------------------------------------

    @Test
    void movesARequestIntoAFolderAndTakesAUniqueNameOnAClash() throws Exception {
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");
        file("demo/users/get.yaml", "name: Other\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo/users/get-2.yaml", ok("store.move", Map.of("path", "demo/get.yaml", "to", "demo/users")));
        assertFalse(exists("demo/get.yaml"));
        assertTrue(read("demo/users/get.yaml").contains("name: Other"), "nothing is replaced");
        assertTrue(read("demo/users/get-2.yaml").contains("name: Get"));
    }

    @Test
    void movesAFolderWithEverythingInIt() throws Exception {
        file("demo/a/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        file("demo/a/deep/y.yaml", "name: Y\nmethod: GET\nurl: https://x.io\n");
        file("demo/b/z.yaml", "name: Z\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo/b/a", ok("store.move", Map.of("path", "demo/a", "to", "demo/b")));
        assertTrue(exists("demo/b/a/x.yaml"));
        assertTrue(exists("demo/b/a/deep/y.yaml"));
        assertFalse(exists("demo/a"));
    }

    @Test
    void aFolderThatWouldCollideIsRefused() throws Exception {
        file("demo/a/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        file("demo/b/a/y.yaml", "name: Y\nmethod: GET\nurl: https://x.io\n");
        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/a", "to", "demo/b")));
        assertTrue(exists("demo/a/x.yaml"));
    }

    @Test
    void aFolderCannotMoveIntoItselfOrADescendant() throws Exception {
        file("demo/a/inner/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/a", "to", "demo/a")));
        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/a", "to", "demo/a/inner")));
        assertTrue(exists("demo/a/inner/x.yaml"));
    }

    @Test
    void aCollectionCannotMoveAndNothingMovesToTheRootOrAnEnvironmentsFolder() throws Exception {
        file("demo/collection.yaml", "name: Demo\n");
        file("demo/environments/dev.yaml", "name: Dev\n");
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");
        file("other/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");

        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo", "to", "other")));
        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/get.yaml", "to", ".")));
        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/get.yaml", "to", "demo/environments")));
        assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/collection.yaml", "to", "other")));
        assertEquals(RpcException.STORE_FAILED, errorCode("store.move", Map.of("path", "demo/get.yaml", "to", "nope")));
        assertTrue(exists("demo/get.yaml"));
    }

    @Test
    void movingToTheCurrentParentIsANoOp() throws Exception {
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");
        assertEquals("demo/get.yaml", ok("store.move", Map.of("path", "demo/get.yaml", "to", "demo")));
        assertEquals(Set.of("get.yaml"), names(workspace.resolve("demo")));
    }

    // --- duplicate ------------------------------------------------------------------------

    @Test
    void duplicatesARequestNextToTheOriginal() throws Exception {
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\nx-custom: 1\n");

        assertEquals("demo/get-copy.yaml", ok("store.duplicate", Map.of("path", "demo/get.yaml")));
        assertEquals("demo/get-copy-2.yaml", ok("store.duplicate", Map.of("path", "demo/get.yaml")));

        String copy = read("demo/get-copy.yaml");
        assertTrue(copy.contains("name: Get copy"), copy);
        assertTrue(copy.contains("x-custom: 1"), copy);
        assertTrue(read("demo/get.yaml").contains("name: Get\n"), "the original is untouched");
    }

    @Test
    void duplicatesAFolderRecursively() throws Exception {
        file("demo/a/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        file("demo/a/deep/y.yaml", "name: Y\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo/a copy", ok("store.duplicate", Map.of("path", "demo/a")));
        assertEquals(read("demo/a/x.yaml"), read("demo/a copy/x.yaml"));
        assertEquals(read("demo/a/deep/y.yaml"), read("demo/a copy/deep/y.yaml"));
        assertEquals("demo/a copy 2", ok("store.duplicate", Map.of("path", "demo/a")));
    }

    @Test
    void duplicatesACollectionWithItsEnvironmentsAndRenamesTheCopy() throws Exception {
        file("demo/collection.yaml", "name: Demo\nvariables:\n- name: v\n  value: '1'\n  enabled: true\n");
        file("demo/environments/dev.yaml", "name: Dev\nvariables: []\n");
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");

        assertEquals("demo copy", ok("store.duplicate", Map.of("path", "demo")));
        assertTrue(exists("demo copy/environments/dev.yaml"));
        assertTrue(exists("demo copy/get.yaml"));
        String collection = read("demo copy/collection.yaml");
        assertTrue(collection.contains("name: Demo copy"), collection);
        assertTrue(collection.contains("name: v"), "variables are copied: " + collection);
        assertTrue(read("demo/collection.yaml").contains("name: Demo\n"));
    }

    @Test
    void aDuplicateLeavesSymlinksBehind() throws Exception {
        file("demo/a/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        Path outside = Files.createTempDirectory("ping-outside");
        try {
            Files.writeString(outside.resolve("secret.txt"), "secret");
            try {
                Files.createSymbolicLink(workspace.resolve("demo/a/link"), outside);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "symlinks are not available here");
            }
            assertEquals("demo/a copy", ok("store.duplicate", Map.of("path", "demo/a")));
            assertEquals(Set.of("x.yaml"), names(workspace.resolve("demo/a copy")),
                    "a symlink must not be followed or copied");
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    // --- create folder --------------------------------------------------------------------

    @Test
    void createsFoldersThatAreSanitisedAndUnique() throws Exception {
        file("demo/collection.yaml", "name: Demo\n");
        assertEquals("demo/Auth", ok("store.create", Map.of("type", "folder", "collection", "demo", "name", "Auth")));
        assertEquals("demo/Auth 2", ok("store.create", Map.of("type", "folder", "collection", "demo", "name", "Auth")));
        assertEquals("demo/a b", ok("store.create", Map.of("type", "folder", "collection", "demo", "name", "a/b")));
        assertEquals("New", ok("store.create", Map.of("type", "folder", "name", "New")), "empty parent means the root");
        assertTrue(Files.isDirectory(workspace.resolve("demo/Auth")));
        assertEquals(INVALID, errorCode("store.create", Map.of("type", "banana", "name", "x")));
        assertEquals(RpcException.STORE_FAILED,
                errorCode("store.create", Map.of("type", "folder", "collection", "missing", "name", "x")));
    }

    @Test
    void createsAFolderForANameThisLocaleCannotSpell() throws Exception {
        file("demo/collection.yaml", "name: Demo\n");

        // A file name reaches the OS encoded as sun.jnu.encoding, which follows the locale
        // rather than moving to UTF-8 with file.encoding, so under a non-UTF-8 one "日本語"
        // cannot be a path at all. Which name it settles on is the locale's business; landing
        // a real folder instead of throwing is not.
        String path = ok("store.create", Map.of("type", "folder", "collection", "demo", "name", "日本語"));
        assertTrue(Files.isDirectory(workspace.resolve(path)), path);
    }

    @Test
    void creatingARequestStillWorksWithoutAType() throws Exception {
        file("demo/collection.yaml", "name: Demo\n");
        assertEquals("demo/new-request.yaml", ok("store.create", Map.of("collection", "demo", "name", "New request")));
    }

    // --- escapes --------------------------------------------------------------------------

    @Test
    void everyPathParameterIsHeldInsideTheWorkspace() throws Exception {
        file("demo/get.yaml", "name: Get\nmethod: GET\nurl: https://x.io\n");
        file("demo/folder/x.yaml", "name: X\nmethod: GET\nurl: https://x.io\n");
        List<String> bad = List.of("../evil", "demo/../../evil", "/etc/passwd");

        for (String path : bad) {
            assertEquals(INVALID, errorCode("store.rename", Map.of("path", path, "name", "x")), path);
            assertEquals(INVALID, errorCode("store.move", Map.of("path", path, "to", "demo/folder")), path);
            assertEquals(INVALID, errorCode("store.move", Map.of("path", "demo/get.yaml", "to", path)), path);
            assertEquals(INVALID, errorCode("store.duplicate", Map.of("path", path)), path);
            assertEquals(INVALID, errorCode("store.create",
                    Map.of("type", "folder", "collection", path, "name", "x")), path);
        }
        assertEquals("demo/x", ok("store.rename", Map.of("path", "demo/folder", "name", "../../x")),
                "a hostile new name is sanitised into a folder name, never a path");
        assertFalse(Files.exists(workspace.getParent().resolve("x")));
    }
}
