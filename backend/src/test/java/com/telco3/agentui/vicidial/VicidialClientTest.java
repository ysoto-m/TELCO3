package com.telco3.agentui.vicidial;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VicidialClientTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void connectCampaignSendsAllRequiredFieldsIncludingEmptyOnes() throws Exception {
    AtomicReference<String> postedBody = new AtomicReference<>("");
    AtomicReference<String> contentType = new AtomicReference<>("");
    AtomicReference<String> userAgent = new AtomicReference<>("");
    AtomicReference<String> referer = new AtomicReference<>("");
    AtomicReference<String> accept = new AtomicReference<>("");

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/agc/vicidial.php", exchange -> {
      postedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
      referer.set(exchange.getRequestHeaders().getFirst("Referer"));
      accept.set(exchange.getRequestHeaders().getFirst("Accept"));
      byte[] response = "<html><script>var session_name='ok'; var server_ip='1.1.1.1';</script>agc_main.php vdc_db_query.php</html>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(response);
      }
    });
    server.start();

    int port = server.getAddress().getPort();
    VicidialConfigService configService = mock(VicidialConfigService.class);
    when(configService.resolve()).thenReturn(new VicidialConfigService.ResolvedVicidialConfig(
        "http://localhost:" + port, "api", "apiPass", "react_crm", false, "test"
    ));

    VicidialClient client = new VicidialClient(configService, 4000, 4000, 4000, false);
    client.connectToCampaign("48373608", "secret", "1001", "anexo_1001", "IVR", null, null);

    Map<String, String> form = parseForm(postedBody.get());
    assertEquals("0", form.get("DB"));
    assertEquals("641", form.get("JS_browser_height"));
    assertEquals("695", form.get("JS_browser_width"));
    assertEquals("1001", form.get("phone_login"));
    assertEquals("anexo_1001", form.get("phone_pass"));
    assertEquals("", form.get("LOGINvarONE"));
    assertEquals("", form.get("LOGINvarTWO"));
    assertEquals("", form.get("LOGINvarTHREE"));
    assertEquals("", form.get("LOGINvarFOUR"));
    assertEquals("", form.get("LOGINvarFIVE"));
    assertEquals("", form.get("hide_relogin_fields"));
    assertEquals("48373608", form.get("VD_login"));
    assertEquals("secret", form.get("VD_pass"));
    assertEquals("IVR", form.get("VD_campaign"));

    assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
    assertTrue(userAgent.get().contains("Mozilla"));
    assertEquals("http://localhost:" + port + "/agc/vicidial.php", referer.get());
    assertEquals("text/html,*/*", accept.get());
  }

  @Test
  void evaluateCampaignConnectBodyDetectsLoginPageAsFailure() {
    VicidialClient client = new VicidialClient(mock(VicidialConfigService.class), 4000, 4000, 4000, false);
    VicidialClient.ConnectOutcome outcome = client.evaluateCampaignConnectBody("<input name=\"VD_login\" /><input name=\"VD_pass\" />");
    assertEquals(VicidialClient.ConnectOutcome.STILL_LOGIN_PAGE, outcome);
  }

  @Test
  void evaluateCampaignConnectBodyDetectsInvalidCredentials() {
    VicidialClient client = new VicidialClient(mock(VicidialConfigService.class), 4000, 4000, 4000, false);
    VicidialClient.ConnectOutcome outcome = client.evaluateCampaignConnectBody("Login incorrect");
    assertEquals(VicidialClient.ConnectOutcome.INVALID_CREDENTIALS, outcome);
  }

  @Test
  void evaluateCampaignConnectBodyDetectsSuccessByAgcMarkers() {
    VicidialClient client = new VicidialClient(mock(VicidialConfigService.class), 4000, 4000, 4000, false);
    VicidialClient.ConnectOutcome outcome = client.evaluateCampaignConnectBody("<html>vdc_db_query.php ... agc_main.php</html>");
    assertEquals(VicidialClient.ConnectOutcome.SUCCESS, outcome);
  }

  @Test
  void connectCampaignMapsReadTimeoutToVicidialUnreachable() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/agc/vicidial.php", exchange -> {
      try {
        Thread.sleep(500);
      } catch (InterruptedException ignored) {
      }
      byte[] response = "<html>late</html>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(response);
      }
    });
    server.start();

    VicidialConfigService configService = mock(VicidialConfigService.class);
    when(configService.resolve()).thenReturn(new VicidialConfigService.ResolvedVicidialConfig(
        "http://localhost:" + server.getAddress().getPort(), "api", "apiPass", "react_crm", false, "test"
    ));

    VicidialClient client = new VicidialClient(configService, 100, 100, 4000, false);

    VicidialServiceException ex = assertThrows(VicidialServiceException.class,
        () -> client.connectCampaign("48373608", "secret", "1001", "IVR"));
    assertEquals("VICIDIAL_UNREACHABLE", ex.code());
  }

  private Map<String, String> parseForm(String body) {
    Map<String, String> result = new LinkedHashMap<>();
    if (body == null || body.isBlank()) {
      return result;
    }
    Arrays.stream(body.split("&"))
        .map(part -> part.split("=", 2))
        .forEach(kv -> {
          String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
          String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
          result.put(key, value);
        });
    return result;
  }
}
