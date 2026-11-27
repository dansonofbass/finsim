package io.github.mhnuk2007.finsim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentationTests {

  @Value("${local.server.port}")
  int port;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        BodyHandlers.ofString());
  }

  @Test
  void exposesOpenApiDocumentWithMetadata() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.info.title")).isEqualTo("FinSim API");
    assertThat(JsonPath.<String>read(response.body(), "$.info.version")).isEqualTo("v1");
    assertThat(
            JsonPath.<Object>read(
                response.body(), "$.paths['/api/v1/networks/{networkId}/transfers']"))
        .isNotNull();
    assertThat(
            JsonPath.<Object>read(
                response.body(), "$.paths['/api/v1/networks/{networkId}/directory']"))
        .isNotNull();
    assertThat(
            JsonPath.<Object>read(
                response.body(), "$.paths['/api/v1/transfers/{transactionId}/trace']"))
        .isNotNull();
  }

  @Test
  void redirectsSwaggerUiToInteractivePage() throws Exception {
    HttpResponse<String> response = get("/swagger-ui.html");

    assertThat(response.statusCode()).isIn(200, 302);
    if (response.statusCode() == 302) {
      assertThat(response.headers().firstValue("Location").orElse("")).contains("/swagger-ui/");
    }
  }

  @Test
  void productionProxyRoutesDocumentationToApiAndPreservesWebFallback() throws Exception {
    Path root = Path.of("..").toAbsolutePath().normalize();
    Testcontainers.exposeHostPorts(port);
    // Keep the production upstream names and ports; bridge to this test's real Spring app.
    try (Network network = Network.newNetwork();
        GenericContainer<?> api =
            new GenericContainer<>("caddy:2-alpine")
                .withNetwork(network)
                .withNetworkAliases("finsim-api")
                .waitingFor(Wait.forLogMessage(".*serving initial configuration.*", 1))
                .withCopyToContainer(
                    Transferable.of(
                        ":8080 {\n reverse_proxy host.testcontainers.internal:" + port + "\n}\n"),
                    "/etc/caddy/Caddyfile");
        GenericContainer<?> web =
            new GenericContainer<>("caddy:2-alpine")
                .withNetwork(network)
                .withNetworkAliases("finsim-web")
                .waitingFor(Wait.forLogMessage(".*serving initial configuration.*", 1))
                .withCopyFileToContainer(
                    MountableFile.forHostPath(root.resolve("web/Caddyfile")),
                    "/etc/caddy/Caddyfile")
                .withCopyFileToContainer(
                    MountableFile.forHostPath(root.resolve("web/index.html")), "/srv/index.html");
        GenericContainer<?> proxy =
            new GenericContainer<>("caddy:2-alpine")
                .withNetwork(network)
                .withEnv("DOMAIN_NAME", ":80")
                .withExposedPorts(80)
                .withCopyFileToContainer(
                    MountableFile.forHostPath(root.resolve("deploy/caddy/Caddyfile")),
                    "/etc/caddy/Caddyfile")) {
      api.start();
      web.start();
      proxy.start();
      String base = "http://" + proxy.getHost() + ":" + proxy.getMappedPort(80);

      HttpResponse<String> landing = getAt(base, "/swagger-ui.html");
      assertThat(landing.statusCode()).isEqualTo(302);
      String location = landing.headers().firstValue("Location").orElseThrow();
      assertThat(location).endsWith("/swagger-ui/index.html");

      HttpResponse<String> ui = getAt(base, URI.create(base).resolve(location).getRawPath());
      assertThat(ui.statusCode()).isEqualTo(200);
      assertThat(ui.headers().firstValue("Content-Type").orElseThrow()).contains("text/html");
      assertThat(ui.body()).contains("Swagger UI").doesNotContain("FinSim Cockpit");
      HttpResponse<String> asset = getAt(base, "/swagger-ui/swagger-ui.css");
      assertThat(asset.statusCode()).isEqualTo(200);
      assertThat(asset.headers().firstValue("Content-Type").orElseThrow()).contains("text/css");
      assertThat(asset.body()).contains(".swagger-ui");

      HttpResponse<String> docs = getAt(base, "/v3/api-docs");
      assertThat(docs.statusCode()).isEqualTo(200);
      assertThat(docs.headers().firstValue("Content-Type").orElseThrow())
          .contains("application/json");
      assertThat(JsonPath.<String>read(docs.body(), "$.info.title")).isEqualTo("FinSim API");
      HttpResponse<String> config = getAt(base, "/v3/api-docs/swagger-config");
      assertThat(config.statusCode()).isEqualTo(200);
      assertThat(JsonPath.<String>read(config.body(), "$.url")).endsWith("/v3/api-docs");

      HttpResponse<String> cockpit = getAt(base, "/");
      assertThat(cockpit.statusCode()).isEqualTo(200);
      assertThat(cockpit.body()).contains("FinSim Cockpit");
      assertThat(cockpit.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
      assertThat(cockpit.headers().firstValue("X-Frame-Options")).contains("DENY");

      for (String path :
          new String[] {
            "/api/v1/networks",
            "/actuator/health",
            "/actuator/health/readiness",
            "/swagger-ui/missing.css",
            "/v3/api-docs/missing"
          }) {
        HttpResponse<String> direct = get(path);
        HttpResponse<String> proxied = getAt(base, path);
        assertThat(proxied.statusCode()).as(path).isEqualTo(direct.statusCode());
        assertThat(proxied.headers().firstValue("Content-Type"))
            .as(path)
            .isEqualTo(direct.headers().firstValue("Content-Type"));
        assertThat(proxied.body()).as(path).doesNotContain("FinSim Cockpit");
        if (direct.statusCode() == 200) {
          assertThat(proxied.body()).as(path).isEqualTo(direct.body());
        }
      }
      assertThat(getAt(base, "/unrelated/path").body()).isEqualTo(cockpit.body());
    }
  }

  private HttpResponse<String> getAt(String base, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofString());
  }
}
