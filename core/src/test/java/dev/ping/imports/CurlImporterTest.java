package dev.ping.imports;

import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A corpus of real-world command shapes. Every case is also written to YAML and read back,
 * because an import is only useful if what it produced survives being saved.
 */
class CurlImporterTest {

    @TempDir
    Path workspace;

    private ImportResult parse(String command) throws IOException {
        ImportResult result = CurlImporter.parse(command);
        assertRoundTrips(result.request());
        return result;
    }

    /** Saving and reloading must lose nothing: a second save produces the identical file. */
    private void assertRoundTrips(StoredRequest request) throws IOException {
        YamlStore store = new YamlStore();
        store.write(workspace, "c/one.yaml", request);
        StoredRequest reloaded = store.read(workspace, "c/one.yaml");
        assertEquals(request.method(), reloaded.method());
        assertEquals(request.url(), reloaded.url());
        assertEquals(request.body(), reloaded.body());
        assertEquals(request.auth(), reloaded.auth());
        store.write(workspace, "c/two.yaml", reloaded);
        assertEquals(Files.readString(workspace.resolve("c/one.yaml")),
                Files.readString(workspace.resolve("c/two.yaml")));
    }

    private static RequestSpec.Param param(String name, String value) {
        return new RequestSpec.Param(name, value, true);
    }

    @Test
    void aBareGet() throws IOException {
        ImportResult result = parse("curl https://api.example.com/todos/1");
        StoredRequest request = result.request();
        assertEquals("GET", request.method());
        assertEquals("https://api.example.com/todos/1", request.url());
        assertEquals("api.example.com/todos/1", request.name());
        assertNull(request.body());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void theQueryBecomesDecodedParamsAndTheFragmentIsDropped() throws IOException {
        StoredRequest request = parse("curl 'https://x.io/a?b=1&c=hello%20world&d#frag'").request();
        assertEquals("https://x.io/a", request.url());
        assertEquals(List.of(param("b", "1"), param("c", "hello world"), param("d", "")), request.query());
    }

    @Test
    void aMissingSchemeGetsHttpLikeCurl() throws IOException {
        assertEquals("http://localhost:3000/x", parse("curl localhost:3000/x").request().url());
    }

    @Test
    void anExplicitJsonBodyDropsTheRedundantContentType() throws IOException {
        StoredRequest request = parse("curl -X POST https://x.io -H 'Content-Type: application/json' "
                + "-d '{\"a\":1}'").request();
        assertEquals("POST", request.method());
        assertEquals(new RequestSpec.Body("json", "{\"a\":1}", null, null), request.body());
        assertTrue(request.headers().isEmpty());
    }

    @Test
    void aJsonCharsetContentTypeIsKeptOnTheBody() throws IOException {
        StoredRequest request = parse("curl https://x.io -H 'content-type: application/json; charset=utf-8' "
                + "-d '{}'").request();
        assertEquals(new RequestSpec.Body("json", "{}", "application/json; charset=utf-8", null), request.body());
    }

    @Test
    void dataImpliesPostAndJsonIsRecognisedWithoutAContentType() throws IOException {
        StoredRequest request = parse("curl https://x.io -d '[1,2]'").request();
        assertEquals("POST", request.method());
        assertEquals("json", request.body().type());
    }

    @Test
    void urlencodedDataBecomesFormFields() throws IOException {
        StoredRequest request = parse("curl https://x.io -d 'a=1&b=two%20words' -d c=3").request();
        assertEquals("POST", request.method());
        assertEquals(new RequestSpec.Body("form", null, null,
                List.of(param("a", "1"), param("b", "two words"), param("c", "3"))), request.body());
    }

    @Test
    void dataThatIsNeitherJsonNorPairsStaysRaw() throws IOException {
        assertEquals(new RequestSpec.Body("raw", "hello world", null, null),
                parse("curl https://x.io -d 'hello world'").request().body());
        assertEquals(new RequestSpec.Body("raw", "hello", "text/plain", null),
                parse("curl https://x.io -H 'Content-Type: text/plain' -d hello").request().body());
    }

    @Test
    void dataUrlencodeEncodesThenDecodesIntoAField() throws IOException {
        StoredRequest request = parse("curl https://x.io --data-urlencode 'a=x y&z' --data-urlencode 'b=2'")
                .request();
        assertEquals("form", request.body().type());
        assertEquals(param("a", "x y&z"), request.body().fields().get(0));
    }

    @Test
    void whatTheAppsOwnCurlExportEmitsComesBackAsTheSameRequest() throws IOException {
        // The exact shape lib/curl.ts produces for a form POST with a query and a header.
        StoredRequest request = parse("curl -X POST 'http://127.0.0.1:8791/curl?existing=1&from=curl' \\\n"
                + "  -H 'X-Curl: yes' \\\n"
                + "  --data-urlencode 'a=1'").request();
        assertEquals("POST", request.method());
        assertEquals("http://127.0.0.1:8791/curl", request.url());
        assertEquals(List.of(param("existing", "1"), param("from", "curl")), request.query());
        assertEquals(List.of(param("X-Curl", "yes")), request.headers());
        assertEquals(new RequestSpec.Body("form", null, null, List.of(param("a", "1"))), request.body());
    }

    @Test
    void aChromeStyleCopyWithAnsiCQuoting() throws IOException {
        ImportResult result = parse("curl 'https://x.io/api/items' \\\n"
                + "  -H 'accept: */*' \\\n"
                + "  -H 'content-type: application/json' \\\n"
                + "  --data-raw $'{\"msg\":\"it\\'s\\nfine\"}' \\\n"
                + "  --compressed");
        StoredRequest request = result.request();
        assertEquals("POST", request.method());
        assertEquals("{\"msg\":\"it's\nfine\"}", request.body().content());
        assertEquals(List.of(param("accept", "*/*")), request.headers());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void multipartFieldsAndFileUploadsBecomeRows() throws IOException {
        ImportResult result = parse("curl https://x.io/up -F a=1 -F 'note=hi;type=text/plain' "
                + "-F 'file=@photo.png' -F 'doc=@/tmp/r.pdf;type=application/pdf;filename=report.pdf'");
        StoredRequest request = result.request();
        assertEquals("POST", request.method());
        assertEquals(new RequestSpec.Body("multipart", null, null, List.of(
                param("a", "1"), param("note", "hi"),
                new RequestSpec.Param("file", null, true, "photo.png", null, null),
                new RequestSpec.Param("doc", null, true, "/tmp/r.pdf", "report.pdf", "application/pdf"))),
                request.body());
        assertEquals(2, result.warnings().size(), "each file asks to be chosen again: " + result.warnings());
        assertTrue(result.warnings().get(0).contains("photo.png"), result.warnings().toString());
    }

    @Test
    void aTextFieldThatReadsAFileIsStillReported() throws IOException {
        ImportResult result = parse("curl https://x.io -F 'a=<notes.txt'");
        assertNull(result.request().body());
        assertTrue(result.warnings().get(0).contains("takes its text from a file"), result.warnings().toString());
    }

    @Test
    void aFileBodyBecomesAFileBodyNotTheLiteralPath() throws IOException {
        ImportResult result = parse("curl https://x.io -d @payload.json");
        assertEquals(new RequestSpec.Body("file", null, null, null, "payload.json"), result.request().body());
        assertEquals("POST", result.request().method());
        assertTrue(result.warnings().get(0).contains("Choose the file again"), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("removes line breaks")),
                "-d strips newlines and this does not: " + result.warnings());
    }

    @Test
    void dataBinaryAndJsonFilesKeepTheirContentType() throws IOException {
        ImportResult binary = parse("curl https://x.io --data-binary @blob.bin -H 'Content-Type: image/png'");
        assertEquals(new RequestSpec.Body("file", null, "image/png", null, "blob.bin"), binary.request().body());
        assertTrue(binary.request().headers().isEmpty(), "the type moved onto the body");
        assertTrue(binary.warnings().stream().noneMatch(w -> w.contains("removes line breaks")));

        ImportResult json = parse("curl https://x.io --json @body.json");
        assertEquals(new RequestSpec.Body("file", null, "application/json", null, "body.json"), json.request().body());
    }

    @Test
    void aFileBodyMixedWithOtherDataIsReportedAndTheFileLeftOut() throws IOException {
        ImportResult result = parse("curl https://x.io -d a=1 -d @extra.txt");
        assertEquals("form", result.request().body().type());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("mixes a file body")), result.warnings().toString());
    }

    @Test
    void userBecomesBasicAuth() throws IOException {
        StoredRequest request = parse("curl -u alice:s3cr:et https://x.io").request();
        assertEquals(RequestSpec.Auth.basic("alice", "s3cr:et"), request.auth());
        assertEquals(RequestSpec.Auth.basic("alice", null), parse("curl --user alice https://x.io").request().auth());
    }

    @Test
    void aBearerHeaderBecomesAuthNotAHeader() throws IOException {
        StoredRequest request = parse("curl https://x.io -H 'Authorization: Bearer abc.def'").request();
        assertEquals(RequestSpec.Auth.bearer("abc.def"), request.auth());
        assertTrue(request.headers().isEmpty());
        assertEquals(RequestSpec.Auth.bearer("tok"), parse("curl --oauth2-bearer tok https://x.io").request().auth());
    }

    @Test
    void aBasicHeaderIsDecodedIntoBasicAuth() throws IOException {
        // alice:pw
        StoredRequest request = parse("curl https://x.io -H 'Authorization: Basic YWxpY2U6cHc='").request();
        assertEquals(RequestSpec.Auth.basic("alice", "pw"), request.auth());
        assertTrue(request.headers().isEmpty());
    }

    @Test
    void anAuthorizationHeaderWeCannotMapIsKeptAndFlagged() throws IOException {
        ImportResult result = parse("curl https://x.io -H 'Authorization: Digest username=\"a\"' "
                + "-H 'Authorization2: x'");
        assertNull(result.request().auth());
        assertEquals("Authorization", result.request().headers().get(0).name());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Authorization")),
                result.warnings().toString());

        ImportResult notBase64 = parse("curl https://x.io -H 'Authorization: Basic !!!'");
        assertNull(notBase64.request().auth());
        assertEquals(1, notBase64.request().headers().size());
    }

    @Test
    void aCredentialLookingHeaderIsFlaggedWithoutEchoingItsValue() throws IOException {
        ImportResult result = parse("curl https://x.io -H 'X-Api-Key: hunter2-value'");
        assertEquals(param("X-Api-Key", "hunter2-value"), result.request().headers().get(0));
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("X-Api-Key"));
        assertFalse(result.warnings().get(0).contains("hunter2-value"));
    }

    @Test
    void groupedFlagsAndSettings() throws IOException {
        StoredRequest request = parse("curl -sSLk -m 2.5 https://x.io").request();
        assertEquals("normal", request.redirects());
        assertEquals(Boolean.FALSE, request.verifyTls());
        assertEquals(2500, request.timeoutMs());

        StoredRequest plain = parse("curl https://x.io").request();
        assertNull(plain.redirects());
        assertNull(plain.verifyTls());
        assertNull(plain.timeoutMs());
    }

    @Test
    void attachedAndEqualsFormsOfOptions() throws IOException {
        StoredRequest request = parse("curl -XPUT -H'X-A: 1' --header=X-B:2 --url=https://x.io").request();
        assertEquals("PUT", request.method());
        assertEquals(List.of(param("X-A", "1"), param("X-B", "2")), request.headers());
    }

    @Test
    void headAndGetModes() throws IOException {
        assertEquals("HEAD", parse("curl -I https://x.io").request().method());
        assertEquals("DELETE", parse("curl -I -X DELETE https://x.io").request().method(), "-X wins");

        StoredRequest get = parse("curl -G https://x.io -d 'q=1' -d 'r=a b'").request();
        assertEquals("GET", get.method());
        assertNull(get.body());
        assertEquals(List.of(param("q", "1"), param("r", "a b")), get.query());
    }

    @Test
    void jsonFlagSendsAJsonBody() throws IOException {
        StoredRequest request = parse("curl --json '{\"a\":1}' https://x.io").request();
        assertEquals("POST", request.method());
        assertEquals(new RequestSpec.Body("json", "{\"a\":1}", null, null), request.body());
    }

    @Test
    void cookieUserAgentAndReferer() throws IOException {
        ImportResult result = parse("curl -b 'sid=1' -A 'ping/1' -e 'https://ref.io' https://x.io");
        assertEquals(List.of(param("Cookie", "sid=1"), param("User-Agent", "ping/1"),
                param("Referer", "https://ref.io")), result.request().headers());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Cookie")));

        ImportResult file = parse("curl -b cookies.txt https://x.io");
        assertTrue(file.request().headers().isEmpty());
        assertEquals(1, file.warnings().size());
    }

    @Test
    void whatHasNoEquivalentIsReportedAndQuietFlagsAreNot() throws IOException {
        ImportResult result = parse("curl -s -v -i -f --compressed https://x.io --cert c.pem --http2 "
                + "--frobnicate -o out.json");
        assertEquals(4, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("--cert")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("--http2")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("--frobnicate")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("-o")));
    }

    @Test
    void shellVariablesAreLeftAsWrittenWithOneWarning() throws IOException {
        ImportResult result = parse("curl -H \"X-A: $A\" -H \"X-B: $B\" https://x.io/$ID");
        assertEquals("https://x.io/$ID", result.request().url());
        assertEquals(1, result.warnings().stream().filter(w -> w.contains("shell variables")).count());

        ImportResult literal = parse("curl 'https://x.io' -d '{\"$ref\":\"#/a\"}'");
        assertTrue(literal.warnings().isEmpty(), "a $ inside single quotes is data: " + literal.warnings());
    }

    @Test
    void extraUrlsAreReportedAndAPromptPrefixAndExeAreAccepted() throws IOException {
        ImportResult result = parse("$ curl.exe https://a.io https://b.io");
        assertEquals("https://a.io", result.request().url());
        assertTrue(result.warnings().get(0).contains("https://b.io"));
    }

    @Test
    void placeholdersSurviveUntouched() throws IOException {
        StoredRequest request = parse("curl '{{baseUrl}}/items?id={{id}}' -H 'X-T: {{token}}'").request();
        assertEquals("{{baseUrl}}/items", request.url());
        assertEquals(List.of(param("id", "{{id}}")), request.query());
    }

    @Test
    void rejectsWhatIsNotAUsableCurlCommand() {
        for (String input : new String[] {"", "  ", "wget https://x.io", "curl", "curl -X POST",
                "curl -H", "curl 'https://x.io"}) {
            RpcException error = assertThrows(RpcException.class, () -> CurlImporter.parse(input), input);
            assertEquals(RpcException.INVALID_PARAMS, error.code(), input);
        }
    }
}
