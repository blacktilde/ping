package dev.ping.imports;

import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.store.CollectionNode;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiImporterTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info:
              title: Pet Store
              version: '1'
            servers:
              - url: https://{env}.pets.io/v1
                description: Production
                variables:
                  env:
                    default: api
              - url: https://staging.pets.io/v1
            security:
              - bearerAuth: []
            paths:
              /pets:
                parameters:
                  - $ref: '#/components/parameters/Trace'
                get:
                  tags: [pets]
                  summary: List pets
                  description: Lists all pets.
                  parameters:
                    - name: limit
                      in: query
                      required: true
                      schema: {type: integer, default: 20}
                    - name: offset
                      in: query
                      schema: {type: integer, example: 5}
                    - name: X-Request-Id
                      in: header
                      schema: {type: string}
                    - name: Accept
                      in: header
                      schema: {type: string}
                    - name: session
                      in: cookie
                      schema: {type: string}
                  responses:
                    '200':
                      description: A list of pets
                    default:
                      $ref: '#/components/responses/Failure'
                post:
                  tags: [pets]
                  operationId: createPet
                  deprecated: true
                  security: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Pet'
              /pets/{petId}:
                get:
                  tags: [pets, other]
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      description: The pet
                      schema: {type: integer}
                      example: 7
                  security:
                    - basicAuth: []
              /health:
                get:
                  summary: Health
                  security:
                    - keyAuth: []
              /queryKey:
                get:
                  summary: Query key
                  security:
                    - queryAuth: []
              /oauth:
                get:
                  operationId: viaOauth
                  security:
                    - oauth: []
              /upload:
                post:
                  summary: Upload
                  requestBody:
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          properties:
                            note: {type: string, example: hi}
                            photo: {type: string, format: binary}
              /form:
                post:
                  summary: Form
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          properties:
                            a: {type: string, example: '1'}
                            b: {type: integer}
              /raw:
                put:
                  summary: Raw
                  requestBody:
                    content:
                      text/plain:
                        example: hello
              /bin:
                put:
                  summary: Bin
                  requestBody:
                    content:
                      application/octet-stream:
                        schema: {type: string, format: binary}
            components:
              parameters:
                Trace:
                  name: trace
                  in: query
                  schema: {type: string}
              responses:
                Failure:
                  description: Something went wrong
              schemas:
                Pet:
                  type: object
                  properties:
                    id: {type: integer, readOnly: true}
                    name: {type: string, example: rex}
                    tag: {type: string, enum: [dog, cat]}
              securitySchemes:
                bearerAuth: {type: http, scheme: bearer}
                basicAuth: {type: http, scheme: basic}
                keyAuth: {type: apiKey, in: header, name: X-API-Key}
                queryAuth: {type: apiKey, in: query, name: api_key}
                oauth: {type: oauth2, flows: {}}
            """;

    @TempDir
    Path root;

    private static CollectionImporter.Parsed parse(String content) {
        return CollectionImporter.parse(content);
    }

    private static List<StoredRequest> all(ImportedCollection.Folder folder) {
        List<StoredRequest> requests = new ArrayList<>(folder.requests());
        folder.folders().forEach(child -> requests.addAll(all(child)));
        return requests;
    }

    private static StoredRequest named(CollectionImporter.Parsed parsed, String name) {
        return all(parsed.collections().get(0).root()).stream()
                .filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no request named " + name));
    }

    private static boolean warned(CollectionImporter.Parsed parsed, String fragment) {
        return parsed.warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    private static RequestSpec.Param param(String name, String value, boolean enabled) {
        return new RequestSpec.Param(name, value, enabled);
    }

    @Test
    void foldersFollowTheFirstTagAndUntaggedOperationsSitAtTheRoot() {
        ImportedCollection collection = parse(SPEC).collections().get(0);
        assertEquals("Pet Store", collection.name());
        assertEquals(List.of("pets"), collection.root().folders().stream()
                .map(ImportedCollection.Folder::name).toList());
        assertEquals(List.of("List pets", "createPet", "GET /pets/{petId}"),
                collection.root().folders().get(0).requests().stream().map(StoredRequest::name).toList());
        assertTrue(collection.root().requests().stream().anyMatch(r -> r.name().equals("Health")));
    }

    @Test
    void theServerBecomesABaseUrlVariableAndEveryServerAnEnvironment() {
        ImportedCollection collection = parse(SPEC).collections().get(0);
        assertEquals(param("baseUrl", "https://api.pets.io/v1", true), collection.variables().get(0));
        assertEquals(List.of("Production", "staging.pets.io"),
                collection.environments().stream().map(ImportedCollection.Environment::name).toList());
        assertEquals(List.of(param("baseUrl", "https://staging.pets.io/v1", true)),
                collection.environments().get(1).variables());
    }

    @Test
    void theInfoDescriptionBecomesTheCollectionNotes() {
        assertEquals("Manages pets.\n\nSee **docs**.", parse("""
                openapi: 3.0.0
                info:
                  title: T
                  description: |
                    Manages pets.

                    See **docs**.
                servers: [{url: 'https://x.io'}]
                paths: {}""").collections().get(0).docs());
        assertNull(parse(SPEC).collections().get(0).docs());
    }

    @Test
    void aSingleServerMakesNoEnvironmentsAndMissingOrRelativeServersAreWarned() {
        CollectionImporter.Parsed one = parse("""
                openapi: 3.1.0
                info: {title: One}
                servers: [{url: 'https://x.io'}]
                paths: {}""");
        assertTrue(one.collections().get(0).environments().isEmpty());
        assertTrue(one.warnings().isEmpty(), one.warnings().toString());

        assertTrue(warned(parse("openapi: 3.0.0\ninfo: {title: N}\npaths: {}"), "lists no servers"));
        assertTrue(warned(parse("openapi: 3.0.0\ninfo: {title: R}\nservers: [{url: /api/v3}]\npaths: {}"), "relative"));
    }

    @Test
    void pathParametersBecomeVariablesAndExamplesBecomeCollectionVariables() {
        CollectionImporter.Parsed parsed = parse(SPEC);
        StoredRequest get = named(parsed, "GET /pets/{petId}");
        assertEquals("{{baseUrl}}/pets/{{petId}}", get.url());
        assertTrue(parsed.collections().get(0).variables().contains(param("petId", "7", true)));
    }

    @Test
    void queryAndHeaderParametersAreRowsRequiredOnesEnabled() {
        StoredRequest list = named(parse(SPEC), "List pets");
        assertEquals(List.of(param("trace", "", false), param("limit", "20", true), param("offset", "5", false)),
                list.query());
        assertEquals(List.of(param("X-Request-Id", "", false)), list.headers(),
                "Accept is skipped, and a cookie parameter is not a header");
    }

    @Test
    void anOperationParameterOverridesAPathLevelOneOfTheSameName() {
        StoredRequest request = parse("""
                openapi: 3.0.0
                info: {title: T}
                servers: [{url: 'https://x.io'}]
                paths:
                  /a:
                    parameters:
                      - {name: q, in: query, required: true, schema: {type: string, default: base}}
                    get:
                      parameters:
                        - {name: q, in: query, schema: {type: string, default: own}}
                """).collections().get(0).root().requests().get(0);
        assertEquals(List.of(param("q", "own", false)), request.query());
    }

    @Test
    void jsonBodiesUseTheSchemaLeavingOutReadOnlyProperties() {
        RequestSpec.Body body = named(parse(SPEC), "createPet").body();
        assertEquals("json", body.type());
        assertEquals("{\n  \"name\" : \"rex\",\n  \"tag\" : \"dog\"\n}", body.content());
    }

    @Test
    void anExplicitBodyExampleBeatsTheSchema() {
        RequestSpec.Body body = parse("""
                openapi: 3.0.0
                info: {title: T}
                servers: [{url: 'https://x.io'}]
                paths:
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {a: {type: string}}}
                            examples:
                              first:
                                value: {a: from-example}
                """).collections().get(0).root().requests().get(0).body();
        assertTrue(body.content().contains("from-example"), body.content());
    }

    @Test
    void formMultipartRawAndBinaryBodies() {
        CollectionImporter.Parsed parsed = parse(SPEC);
        assertEquals(new RequestSpec.Body("form", null, null, List.of(param("a", "1", true), param("b", "0", true))),
                named(parsed, "Form").body());
        assertEquals(new RequestSpec.Body("multipart", null, null, List.of(param("note", "hi", true),
                        new RequestSpec.Param("photo", null, true, null, null, "application/octet-stream"))),
                named(parsed, "Upload").body());
        assertEquals(new RequestSpec.Body("raw", "hello", "text/plain", null), named(parsed, "Raw").body());
        assertEquals(new RequestSpec.Body("file", null, "application/octet-stream", null, null),
                named(parsed, "Bin").body(), "a file body with no file chosen yet");
        assertTrue(warned(parsed, "photo"), parsed.warnings().toString());
        assertTrue(warned(parsed, "PUT /bin sends a file"), parsed.warnings().toString());
        assertTrue(warned(parsed, "Choose the file"), parsed.warnings().toString());
    }

    @Test
    void authIsAlwaysAReferenceNeverAValue() {
        CollectionImporter.Parsed parsed = parse(SPEC);
        assertEquals(RequestSpec.Auth.bearer("{{bearerAuth}}"), named(parsed, "List pets").auth(), "the global default");
        assertNull(named(parsed, "createPet").auth(), "security: [] turns it off");
        assertEquals(RequestSpec.Auth.basic("{{basicAuth_username}}", "{{basicAuth_password}}"),
                named(parsed, "GET /pets/{petId}").auth());
        assertEquals(RequestSpec.Auth.apiKey("X-API-Key", "{{keyAuth}}", "header"), named(parsed, "Health").auth());
        assertEquals(RequestSpec.Auth.apiKey("api_key", "{{queryAuth}}", "query"), named(parsed, "Query key").auth());
        assertNull(named(parsed, "viaOauth").auth());
    }

    @Test
    void eachSchemeIsExplainedOnceWithTheSecretNamesToSet() {
        CollectionImporter.Parsed parsed = parse(SPEC);
        assertEquals(1, parsed.warnings().stream().filter(w -> w.contains("\"bearerAuth\"")).count());
        assertTrue(warned(parsed, "secret named basicAuth_username and basicAuth_password"), parsed.warnings().toString());
        assertTrue(warned(parsed, "\"oauth\" (oauth2) is not supported"), parsed.warnings().toString());
    }

    @Test
    void docsCarryTheDescriptionParametersAndResponses() {
        String docs = named(parse(SPEC), "List pets").docs();
        assertTrue(docs.startsWith("Lists all pets."), docs);
        assertTrue(docs.contains("- `limit` (query, integer, required)"), docs);
        assertTrue(docs.contains("- `200` — A list of pets"), docs);
        assertTrue(docs.contains("- `default` — Something went wrong"), docs);
        assertTrue(named(parse(SPEC), "createPet").docs().contains("**Deprecated.**"));
    }

    @Test
    void unsupportedPartsAreReportedOnce() {
        CollectionImporter.Parsed parsed = parse("""
                openapi: 3.0.0
                info: {title: T}
                servers: [{url: 'https://x.io'}]
                webhooks:
                  event: {post: {summary: e}}
                paths:
                  /a:
                    servers: [{url: 'https://other.io'}]
                    get:
                      callbacks: {cb: {}}
                      parameters:
                        - {name: c1, in: cookie, schema: {type: string}}
                        - {name: c2, in: cookie, schema: {type: string}}
                    post:
                      callbacks: {cb: {}}
                      servers: [{url: 'https://third.io'}]
                      parameters:
                        - {$ref: 'https://example.com/p.yaml#/P'}
                """);
        for (String fragment : new String[] {"Webhooks", "Callbacks", "Servers declared", "Cookie parameters",
                "external reference"}) {
            assertEquals(1, parsed.warnings().stream().filter(w -> w.contains(fragment)).count(),
                    fragment + " in " + parsed.warnings());
        }
    }

    @Test
    void jsonDocumentsWorkEvenWhenIndentedWithTabs() {
        CollectionImporter.Parsed parsed = parse("{\n\t\"openapi\": \"3.0.0\",\n\t\"info\": {\"title\": \"Tabs\"},\n"
                + "\t\"servers\": [{\"url\": \"https://x.io\"}],\n\t\"paths\": {\"/a\": {\"get\": {\"summary\": \"A\"}}}\n}");
        assertEquals("Tabs", parsed.collections().get(0).name());
        assertEquals("{{baseUrl}}/a", parsed.collections().get(0).root().requests().get(0).url());
    }

    @Test
    void swaggerTwoAndNonOpenApiDocumentsAreRejectedWithAReason() {
        RpcException swagger = assertThrows(RpcException.class,
                () -> parse("swagger: '2.0'\ninfo: {title: Old}\npaths: {}"));
        assertTrue(swagger.getMessage().contains("Swagger 2.0 is not supported"), swagger.getMessage());
        RpcException other = assertThrows(RpcException.class, () -> parse("name: x\nitems: []"));
        assertTrue(other.getMessage().contains("OpenAPI 3"), other.getMessage());
    }

    @Test
    void aReferenceCycleInParametersOrSchemasDoesNotHang() {
        CollectionImporter.Parsed parsed = parse("""
                openapi: 3.0.0
                info: {title: Loop}
                servers: [{url: 'https://x.io'}]
                paths:
                  /a:
                    post:
                      parameters:
                        - {$ref: '#/components/parameters/A'}
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Node'}
                components:
                  parameters:
                    A: {$ref: '#/components/parameters/B'}
                    B: {$ref: '#/components/parameters/A'}
                  schemas:
                    Node:
                      type: object
                      properties:
                        next: {$ref: '#/components/schemas/Node'}
                """);
        assertEquals(1, parsed.collections().get(0).root().requests().size());
        assertTrue(warned(parsed, "never resolves"), parsed.warnings().toString());
    }

    @Test
    void aLargeSpecImportsQuickly() {
        StringBuilder spec = new StringBuilder("""
                openapi: 3.0.0
                info: {title: Big}
                servers: [{url: 'https://x.io'}]
                paths:
                """);
        for (int i = 0; i < 3000; i++) {
            spec.append("  /r").append(i).append(":\n    get:\n      tags: [t").append(i % 20)
                    .append("]\n      summary: Op ").append(i)
                    .append("\n      responses:\n        '200': {description: ok}\n");
        }
        long started = System.nanoTime();
        CollectionImporter.Parsed parsed = parse(spec.toString());
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(3000, all(parsed.collections().get(0).root()).size());
        assertEquals(20, parsed.collections().get(0).root().folders().size());
        assertTrue(millis < 10_000, "took " + millis + " ms");
    }

    @Test
    void writesACollectionThatReadsBackWithNoLiteralCredentials() throws IOException {
        YamlStore store = new YamlStore();
        CollectionWriter.Output output = new CollectionWriter(store).write(root, parse(SPEC));

        assertEquals("Pet Store", output.collections().get(0).path());
        assertEquals(10, output.collections().get(0).requests());
        assertEquals(2, output.collections().get(0).environments());
        assertTrue(output.secrets().isEmpty(), "a spec holds no credentials to lift: " + output.secrets());

        StoredRequest list = store.read(root, "Pet Store/pets/list-pets.yaml");
        assertEquals("{{baseUrl}}/pets", list.url());
        assertEquals(RequestSpec.Auth.bearer("{{bearerAuth}}"), list.auth());
        assertTrue(list.docs().contains("Lists all pets."));

        List<String> folders = store.scan(root).get(0).children().stream()
                .filter(n -> CollectionNode.FOLDER.equals(n.type())).map(CollectionNode::name).toList();
        assertEquals(List.of("pets"), folders);

        try (Stream<Path> files = Files.walk(root)) {
            String everything = files.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }).collect(Collectors.joining("\n"));
            assertFalse(everything.contains("Bearer "), everything);
        }
    }
}
