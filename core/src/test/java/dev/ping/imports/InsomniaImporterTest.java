package dev.ping.imports;

import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.store.StoredRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsomniaImporterTest {

    private static CollectionImporter.Parsed parse(String resources) {
        return CollectionImporter.parse("""
                {"_type": "export", "__export_format": 4, "resources": [%s]}
                """.formatted(resources));
    }

    private static final String WORKSPACE = """
            {"_id": "wrk_1", "_type": "workspace", "name": "Shop"}""";

    private static RequestSpec.Param param(String name, String value, boolean enabled) {
        return new RequestSpec.Param(name, value, enabled);
    }

    private static StoredRequest request(String resource) {
        CollectionImporter.Parsed parsed = parse(WORKSPACE + "," + resource);
        List<StoredRequest> requests = parsed.collections().get(0).root().requests();
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private static boolean warned(CollectionImporter.Parsed parsed, String fragment) {
        return parsed.warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    @Test
    void aWorkspaceBecomesACollectionWithFoldersAndRequests() {
        CollectionImporter.Parsed parsed = parse(WORKSPACE + """
                ,
                {"_id": "fld_1", "_type": "request_group", "parentId": "wrk_1", "name": "Orders"},
                {"_id": "fld_2", "_type": "request_group", "parentId": "fld_1", "name": "Archive"},
                {"_id": "req_1", "_type": "request", "parentId": "fld_1", "name": "List", "method": "GET", "url": "https://s.io/o"},
                {"_id": "req_2", "_type": "request", "parentId": "fld_2", "name": "Old", "method": "get", "url": "https://s.io/old"},
                {"_id": "req_3", "_type": "request", "parentId": "wrk_1", "name": "Ping", "method": "HEAD", "url": "https://s.io/ping"},
                {"_id": "jar_1", "_type": "cookie_jar", "parentId": "wrk_1", "name": "Default Jar"}""");

        ImportedCollection collection = parsed.collections().get(0);
        assertEquals("Shop", collection.name());
        assertEquals("Ping", collection.root().requests().get(0).name());
        ImportedCollection.Folder orders = collection.root().folders().get(0);
        assertEquals("Orders", orders.name());
        assertEquals("List", orders.requests().get(0).name());
        assertEquals("Old", orders.folders().get(0).requests().get(0).name());
        assertEquals("GET", orders.folders().get(0).requests().get(0).method());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void theBaseEnvironmentBecomesCollectionVariablesAndSubEnvironmentsBecomeEnvironments() {
        CollectionImporter.Parsed parsed = parse(WORKSPACE + """
                ,
                {"_id": "env_b", "_type": "environment", "parentId": "wrk_1", "name": "Base Environment",
                    "data": {"baseUrl": "https://s.io", "auth": {"user": "u", "deep": {"x": 1}}, "list": [1, 2], "n": 5}},
                {"_id": "env_p", "_type": "environment", "parentId": "env_b", "name": "Prod",
                    "data": {"baseUrl": "https://prod.s.io", "who": "{{ _.auth.user }}"}}""");

        ImportedCollection collection = parsed.collections().get(0);
        assertEquals(List.of(
                param("baseUrl", "https://s.io", true), param("auth.user", "u", true),
                param("auth.deep.x", "1", true), param("list", "[1,2]", true), param("n", "5", true)),
                collection.variables());
        assertEquals(1, collection.environments().size());
        assertEquals("Prod", collection.environments().get(0).name());
        assertEquals(List.of(param("baseUrl", "https://prod.s.io", true), param("who", "{{auth.user}}", true)),
                collection.environments().get(0).variables());
    }

    @Test
    void variableReferencesAreRewrittenEverywhereAndTagsAreWarnedOnce() {
        CollectionImporter.Parsed parsed = parse(WORKSPACE + """
                ,
                {"_id": "r1", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET",
                    "url": "{{ _.baseUrl }}/x?y={% uuid 'v4' %}",
                    "headers": [{"name": "X-{{ _.h }}", "value": "{{ _.v }}"}, {"name": "X-Off", "value": "1", "disabled": true}]},
                {"_id": "r2", "_type": "request", "parentId": "wrk_1", "name": "B", "method": "GET",
                    "url": "https://s.io", "headers": [{"name": "X-T", "value": "{% response 'body', 'a', 'b' %}"}]}""");

        List<StoredRequest> requests = parsed.collections().get(0).root().requests();
        assertEquals("{{baseUrl}}/x", requests.get(0).url());
        assertEquals(List.of(param("y", "{% uuid 'v4' %}", true)), requests.get(0).query());
        assertEquals(List.of(param("X-{{h}}", "{{v}}", true), param("X-Off", "1", false)),
                requests.get(0).headers());
        assertEquals(1, parsed.warnings().stream().filter(w -> w.contains("template tags")).count(),
                parsed.warnings().toString());
    }

    @Test
    void parametersWinOverTheInlineQueryButAnInlineOnlyQueryIsSplit() {
        StoredRequest withParams = request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET",
                    "url": "https://s.io/a?x=1", "parameters": [{"name": "p", "value": "2"}, {"name": "q", "value": "3", "disabled": true}]}""");
        assertEquals("https://s.io/a", withParams.url());
        assertEquals(List.of(param("p", "2", true), param("q", "3", false)), withParams.query());

        StoredRequest inline = request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET", "url": "https://s.io/a?x=1&y=two%20words"}""");
        assertEquals(List.of(param("x", "1", true), param("y", "two words", true)), inline.query());
    }

    @Test
    void bodiesMapByMimeType() {
        assertEquals(new RequestSpec.Body("json", "{\"a\":1}", null, null), request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "POST", "url": "https://s.io",
                    "body": {"mimeType": "application/json", "text": "{\\"a\\":1}"}}""").body());

        assertEquals(new RequestSpec.Body("form", null, null, List.of(param("a", "1", true), param("b", "2", false))),
                request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "POST", "url": "https://s.io",
                    "body": {"mimeType": "application/x-www-form-urlencoded", "params": [
                        {"name": "a", "value": "1"}, {"name": "b", "value": "2", "disabled": true}]}}""").body());

        assertEquals(new RequestSpec.Body("raw", "hi", "text/plain", null), request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "POST", "url": "https://s.io",
                    "body": {"mimeType": "text/plain", "text": "hi"}}""").body());

        assertNull(request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET", "url": "https://s.io",
                    "body": {}}""").body());
    }

    @Test
    void multipartFileParamsAreReported() {
        CollectionImporter.Parsed parsed = parse(WORKSPACE + """
                ,
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "Up", "method": "POST", "url": "https://s.io",
                    "body": {"mimeType": "multipart/form-data", "params": [
                        {"name": "note", "value": "hi"}, {"name": "photo", "type": "file", "fileName": "/tmp/p.png"}]}}""");
        assertEquals(new RequestSpec.Body("multipart", null, null, List.of(param("note", "hi", true))),
                parsed.collections().get(0).root().requests().get(0).body());
        assertTrue(warned(parsed, "photo"), parsed.warnings().toString());
    }

    @Test
    void authTypesMapAndTheRestAreWarned() {
        assertEquals(RequestSpec.Auth.bearer("{{token}}"), request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET", "url": "https://s.io",
                    "authentication": {"type": "bearer", "token": "{{ _.token }}"}}""").auth());
        assertEquals(RequestSpec.Auth.basic("alice", "pw"), request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET", "url": "https://s.io",
                    "authentication": {"type": "basic", "username": "alice", "password": "pw"}}""").auth());
        assertEquals(RequestSpec.Auth.apiKey("X-Key", "k1", "query"), request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET", "url": "https://s.io",
                    "authentication": {"type": "apikey", "key": "X-Key", "value": "k1", "addTo": "queryParams"}}""").auth());
        assertNull(request("""
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "A", "method": "GET", "url": "https://s.io",
                    "authentication": {"type": "bearer", "token": "t", "disabled": true}}""").auth());

        CollectionImporter.Parsed parsed = parse(WORKSPACE + """
                ,
                {"_id": "r", "_type": "request", "parentId": "wrk_1", "name": "O", "method": "GET", "url": "https://s.io",
                    "authentication": {"type": "oauth2", "grantType": "authorization_code"}}""");
        assertNull(parsed.collections().get(0).root().requests().get(0).auth());
        assertTrue(warned(parsed, "oauth2"), parsed.warnings().toString());
    }

    @Test
    void descriptionsBecomeNotesAndFolderEnvironmentsAreWarned() {
        CollectionImporter.Parsed parsed = parse(WORKSPACE + """
                ,
                {"_id": "f", "_type": "request_group", "parentId": "wrk_1", "name": "G", "environment": {"a": "1"}},
                {"_id": "r", "_type": "request", "parentId": "f", "name": "A", "method": "GET", "url": "https://s.io",
                    "description": "  Lists things.  "}""");
        assertEquals("Lists things.", parsed.collections().get(0).root().folders().get(0).requests().get(0).docs());
        assertTrue(warned(parsed, "Folder \"G\""), parsed.warnings().toString());
    }

    @Test
    void aWorkspaceDescriptionBecomesTheCollectionNotes() {
        CollectionImporter.Parsed parsed = parse("""
                {"_id": "w", "_type": "workspace", "name": "Shop", "description": " Storefront API "},
                {"_id": "w2", "_type": "workspace", "name": "Bare"}""");
        assertEquals("Storefront API", parsed.collections().get(0).docs());
        assertNull(parsed.collections().get(1).docs());
    }

    @Test
    void oneExportCanHoldSeveralWorkspaces() {
        CollectionImporter.Parsed parsed = parse("""
                {"_id": "w1", "_type": "workspace", "name": "One"},
                {"_id": "w2", "_type": "workspace", "name": "Two"},
                {"_id": "r1", "_type": "request", "parentId": "w1", "name": "A", "method": "GET", "url": "https://s.io/1"},
                {"_id": "r2", "_type": "request", "parentId": "w2", "name": "B", "method": "GET", "url": "https://s.io/2"}""");
        assertEquals(2, parsed.collections().size());
        assertEquals("A", parsed.collections().get(0).root().requests().get(0).name());
        assertEquals("B", parsed.collections().get(1).root().requests().get(0).name());
    }

    @Test
    void rejectsExportsItCannotImportAndSaysWhy() {
        String[][] cases = {
            {"{\"_type\": \"export\", \"__export_format\": 3, \"resources\": []}", "format 3"},
            {"{\"_type\": \"export\", \"__export_format\": 4, \"resources\": []}", "no workspace"},
        };
        for (String[] c : cases) {
            RpcException error = assertThrows(RpcException.class, () -> CollectionImporter.parse(c[0]), c[0]);
            assertEquals(RpcException.INVALID_PARAMS, error.code());
            assertTrue(error.getMessage().contains(c[1]), c[0] + " -> " + error.getMessage());
        }
    }
}
