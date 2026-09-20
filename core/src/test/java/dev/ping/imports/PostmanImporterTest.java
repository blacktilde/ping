package dev.ping.imports;

import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.store.StoredRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmanImporterTest {

    private static final String SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    /** A collection named Demo around the given top-level fields and items. */
    private static CollectionImporter.Parsed parse(String extra, String items) {
        return CollectionImporter.parse("""
                {"info": {"name": "Demo", "schema": "%s"}, %s "item": [%s]}
                """.formatted(SCHEMA, extra, items));
    }

    private static StoredRequest only(CollectionImporter.Parsed parsed) {
        List<StoredRequest> requests = parsed.collections().get(0).root().requests();
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private static RequestSpec.Param param(String name, String value, boolean enabled) {
        return new RequestSpec.Param(name, value, enabled);
    }

    private static boolean warned(CollectionImporter.Parsed parsed, String fragment) {
        return parsed.warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    @Test
    void foldersNestAndRequestsKeepTheirNamesAndMethods() {
        CollectionImporter.Parsed parsed = parse("", """
                {"name": "Pets", "item": [
                    {"name": "List", "request": {"method": "GET", "url": "https://x.io/pets"}},
                    {"name": "Inner", "item": [
                        {"name": "Deep", "request": {"method": "delete", "url": "https://x.io/pets/1"}}]}]},
                {"name": "Top", "request": {"method": "POST", "url": "https://x.io/top"}}""");

        ImportedCollection collection = parsed.collections().get(0);
        assertEquals("Demo", collection.name());
        assertEquals("Top", collection.root().requests().get(0).name());
        ImportedCollection.Folder pets = collection.root().folders().get(0);
        assertEquals("Pets", pets.name());
        assertEquals("List", pets.requests().get(0).name());
        assertEquals("DELETE", pets.folders().get(0).requests().get(0).method());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void theCollectionDescriptionBecomesItsNotes() {
        CollectionImporter.Parsed plain = CollectionImporter.parse("""
                {"info": {"name": "D", "description": "  Plain text  ", "schema": "%s"}, "item": []}
                """.formatted(SCHEMA));
        assertEquals("Plain text", plain.collections().get(0).docs());

        CollectionImporter.Parsed object = CollectionImporter.parse("""
                {"info": {"name": "D", "description": {"content": "# Docs", "type": "text/markdown"}, "schema": "%s"}, "item": []}
                """.formatted(SCHEMA));
        assertEquals("# Docs", object.collections().get(0).docs());
        assertNull(parse("", "").collections().get(0).docs());
    }

    @Test
    void collectionVariablesKeepTheirDisabledState() {
        CollectionImporter.Parsed parsed = parse("""
                "variable": [{"key": "baseUrl", "value": "https://x.io"}, {"key": "off", "value": "1", "disabled": true}],
                """, "");
        assertEquals(List.of(param("baseUrl", "https://x.io", true), param("off", "1", false)),
                parsed.collections().get(0).variables());
    }

    @Test
    void theQueryComesFromTheQueryListSoDisabledRowsSurvive() {
        StoredRequest request = only(parse("", """
                {"name": "List", "request": {"method": "GET",
                    "header": [{"key": "Accept", "value": "application/json"}, {"key": "X-Off", "value": "1", "disabled": true}],
                    "url": {"raw": "{{baseUrl}}/pets?limit=10&debug=1", "query": [
                        {"key": "limit", "value": "10"}, {"key": "debug", "value": "1", "disabled": true}]}}}"""));
        assertEquals("{{baseUrl}}/pets", request.url());
        assertEquals(List.of(param("limit", "10", true), param("debug", "1", false)), request.query());
        assertEquals(List.of(param("Accept", "application/json", true), param("X-Off", "1", false)),
                request.headers());
    }

    @Test
    void anAddressBuiltFromPartsAndTheShortStringFormBothWork() {
        StoredRequest parts = only(parse("", """
                {"name": "A", "request": {"method": "GET", "url": {"protocol": "https",
                    "host": ["api", "x", "io"], "port": "8443", "path": ["v1", "pets"]}}}"""));
        assertEquals("https://api.x.io:8443/v1/pets", parts.url());

        StoredRequest shortForm = only(parse("", """
                {"name": "B", "request": "localhost:3000/x"}"""));
        assertEquals("http://localhost:3000/x", shortForm.url());
        assertEquals("GET", shortForm.method());
    }

    @Test
    void aPathVariableIsLeftAsWrittenWithAWarning() {
        CollectionImporter.Parsed parsed = parse("", """
                {"name": "Get", "request": {"method": "GET", "url": {"raw": "{{baseUrl}}/pets/:id"}}}""");
        assertEquals("{{baseUrl}}/pets/:id", only(parsed).url());
        assertTrue(warned(parsed, "path variable"), parsed.warnings().toString());
    }

    @Test
    void jsonBodiesAreRecognisedByLanguageOrByContentType() {
        RequestSpec.Body byLanguage = only(parse("", """
                {"name": "A", "request": {"method": "POST", "url": "https://x.io", "body": {
                    "mode": "raw", "raw": "[1,2]", "options": {"raw": {"language": "json"}}}}}""")).body();
        assertEquals(new RequestSpec.Body("json", "[1,2]", null, null), byLanguage);

        RequestSpec.Body byHeader = only(parse("", """
                {"name": "B", "request": {"method": "POST", "url": "https://x.io",
                    "header": [{"key": "Content-Type", "value": "application/vnd.api+json"}],
                    "body": {"mode": "raw", "raw": "{}"}}}""")).body();
        assertEquals("json", byHeader.type());

        RequestSpec.Body xml = only(parse("", """
                {"name": "C", "request": {"method": "POST", "url": "https://x.io", "body": {
                    "mode": "raw", "raw": "<a/>", "options": {"raw": {"language": "xml"}}}}}""")).body();
        assertEquals(new RequestSpec.Body("raw", "<a/>", "application/xml", null), xml);
    }

    @Test
    void formBodiesKeepDisabledRowsAndFileUploadsBecomeFileRows() {
        CollectionImporter.Parsed parsed = parse("", """
                {"name": "Form", "request": {"method": "POST", "url": "https://x.io",
                    "body": {"mode": "urlencoded", "urlencoded": [
                        {"key": "a", "value": "1"}, {"key": "b", "value": "2", "disabled": true}]}}},
                {"name": "Upload", "request": {"method": "POST", "url": "https://x.io",
                    "body": {"mode": "formdata", "formdata": [
                        {"key": "note", "value": "hi", "type": "text"},
                        {"key": "photo", "type": "file", "src": ["/tmp/p.png"], "contentType": "image/png"}]}}}""");
        List<StoredRequest> requests = parsed.collections().get(0).root().requests();
        assertEquals(new RequestSpec.Body("form", null, null,
                List.of(param("a", "1", true), param("b", "2", false))), requests.get(0).body());
        assertEquals(new RequestSpec.Body("multipart", null, null, List.of(param("note", "hi", true),
                        new RequestSpec.Param("photo", null, true, "/tmp/p.png", null, "image/png"))),
                requests.get(1).body());
        assertTrue(warned(parsed, "photo"), parsed.warnings().toString());
    }

    @Test
    void graphqlBecomesAJsonEnvelope() {
        RequestSpec.Body body = only(parse("", """
                {"name": "Q", "request": {"method": "POST", "url": "https://x.io/graphql",
                    "body": {"mode": "graphql", "graphql": {"query": "{ me { id } }", "variables": "{\\"id\\": 7}"}}}}"""))
                .body();
        assertEquals("json", body.type());
        assertTrue(body.content().contains("\"query\" : \"{ me { id } }\""), body.content());
        assertTrue(body.content().contains("\"id\" : 7"), body.content());
    }

    @Test
    void fileBodiesBecomeFileBodies() {
        CollectionImporter.Parsed parsed = parse("", """
                {"name": "F", "request": {"method": "PUT", "url": "https://x.io",
                    "body": {"mode": "file", "file": {"src": "/tmp/a.bin"}}}}""");
        assertEquals(new RequestSpec.Body("file", null, null, null, "/tmp/a.bin"), only(parsed).body());
        assertTrue(warned(parsed, "/tmp/a.bin"), parsed.warnings().toString());
        assertTrue(warned(parsed, "choose the file again"), parsed.warnings().toString());

        CollectionImporter.Parsed none = parse("", """
                {"name": "F", "request": {"method": "PUT", "url": "https://x.io",
                    "body": {"mode": "binary"}}}""");
        assertEquals("file", only(none).body().type());
        assertNull(only(none).body().file());
        assertTrue(warned(none, "no file chosen"), none.warnings().toString());
    }

    @Test
    void authIsInheritedFromTheNearestLevelAndNoauthStopsIt() {
        CollectionImporter.Parsed parsed = parse("""
                "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "root-token"}]},
                """, """
                {"name": "Open", "auth": {"type": "noauth"}, "item": [
                    {"name": "Anon", "request": {"method": "GET", "url": "https://x.io/a"}}]},
                {"name": "Keys", "auth": {"type": "apikey", "apikey": [
                        {"key": "key", "value": "X-Key"}, {"key": "value", "value": "k1"}, {"key": "in", "value": "query"}]},
                    "item": [
                        {"name": "Keyed", "request": {"method": "GET", "url": "https://x.io/b"}},
                        {"name": "Own", "request": {"method": "GET", "url": "https://x.io/c",
                            "auth": {"type": "basic", "basic": [
                                {"key": "username", "value": "alice"}, {"key": "password", "value": "pw"}]}}}]},
                {"name": "Inherits", "request": {"method": "GET", "url": "https://x.io/d"}}""");

        ImportedCollection.Folder root = parsed.collections().get(0).root();
        assertEquals(RequestSpec.Auth.bearer("root-token"), root.requests().get(0).auth());
        assertNull(root.folders().get(0).requests().get(0).auth(), "noauth stops inheritance");
        assertEquals(RequestSpec.Auth.apiKey("X-Key", "k1", "query"),
                root.folders().get(1).requests().get(0).auth());
        assertEquals(RequestSpec.Auth.basic("alice", "pw"), root.folders().get(1).requests().get(1).auth());
    }

    @Test
    void theOlderObjectShapeOfAuthAttributesIsRead() {
        StoredRequest request = only(parse("", """
                {"name": "A", "request": {"method": "GET", "url": "https://x.io",
                    "auth": {"type": "bearer", "bearer": {"token": "t0k"}}}}"""));
        assertEquals(RequestSpec.Auth.bearer("t0k"), request.auth());
    }

    @Test
    void unsupportedAuthIsWarnedOnceWhereItIsDeclared() {
        CollectionImporter.Parsed parsed = parse("""
                "auth": {"type": "oauth2", "oauth2": [{"key": "accessToken", "value": "x"}]},
                """, """
                {"name": "One", "request": {"method": "GET", "url": "https://x.io/1"}},
                {"name": "Two", "request": {"method": "GET", "url": "https://x.io/2"}}""");
        assertEquals(1, parsed.warnings().stream().filter(w -> w.contains("oauth2")).count(),
                parsed.warnings().toString());
        assertNull(parsed.collections().get(0).root().requests().get(0).auth());
    }

    @Test
    void anAuthorizationHeaderIsFoldedIntoAuthOnlyWhenNothingElseSetsIt() {
        StoredRequest request = only(parse("", """
                {"name": "A", "request": {"method": "GET", "url": "https://x.io",
                    "header": [{"key": "Authorization", "value": "Bearer abc"}, {"key": "X-Other", "value": "1"}]}}"""));
        assertEquals(RequestSpec.Auth.bearer("abc"), request.auth());
        assertEquals(List.of(param("X-Other", "1", true)), request.headers());
    }

    @Test
    void scriptsAndDescriptionsBecomeNotesAndCollectionScriptsAreWarned() {
        CollectionImporter.Parsed parsed = parse("""
                "event": [{"listen": "prerequest", "script": {"exec": ["console.log('root')"]}}],
                """, """
                {"name": "Create", "event": [
                        {"listen": "test", "script": {"exec": ["pm.test('ok', () => {});", "// done"]}},
                        {"listen": "prerequest", "script": {"exec": [" "]}}],
                    "request": {"method": "POST", "url": "https://x.io", "description": "Adds a pet."}},
                {"name": "Plain", "request": {"method": "GET", "url": "https://x.io"}}""");

        List<StoredRequest> requests = parsed.collections().get(0).root().requests();
        String docs = requests.get(0).docs();
        assertTrue(docs.startsWith("Adds a pet."), docs);
        assertTrue(docs.contains("## Not imported"), docs);
        assertTrue(docs.contains("### Test script"), docs);
        assertTrue(docs.contains("pm.test('ok', () => {});\n// done"), docs);
        assertFalse(docs.contains("Pre-request"), "a blank script is not worth a note: " + docs);
        assertNull(requests.get(1).docs());
        assertTrue(warned(parsed, "Collection \"Demo\" has a pre-request script"), parsed.warnings().toString());
    }

    @Test
    void aScriptContainingAFenceIsFencedWithTildes() {
        StoredRequest request = only(parse("", """
                {"name": "A", "event": [{"listen": "test", "script": {"exec": "const s = '```';"}}],
                    "request": {"method": "GET", "url": "https://x.io"}}"""));
        assertTrue(request.docs().contains("~~~~js"), request.docs());
    }

    @Test
    void rejectsWhatItCannotImportAndSaysWhy() {
        String[][] cases = {
            {"a: [unclosed", "not valid JSON or YAML"},
            {"not json", "not a Postman collection"},
            {"[1]", "not a Postman collection"},
            {"{\"name\": \"x\"}", "not a Postman collection"},
            {"{\"_postman_variable_scope\": \"environment\", \"values\": []}", "environment export"},
            {"{\"info\": {\"schema\": \"https://schema.getpostman.com/json/collection/v1.0.0/collection.json\"}}",
                "v1 is not supported"},
        };
        for (String[] c : cases) {
            RpcException error = assertThrows(RpcException.class, () -> CollectionImporter.parse(c[0]), c[0]);
            assertEquals(RpcException.INVALID_PARAMS, error.code());
            assertTrue(error.getMessage().contains(c[1]), c[0] + " -> " + error.getMessage());
        }
    }
}
