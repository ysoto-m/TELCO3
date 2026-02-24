package com.telco3.agentui.vicidial;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/agc/vicidial.php", exchange -> {
      postedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "<html>Logout AGENT_ SESSION_name</html>".getBytes(StandardCharsets.UTF_8);
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

    VicidialClient client = new VicidialClient(configService, 4000, 4000, 30);
    client.connectCampaign("48373608", "secret", "1001", "IVR");

    Map<String, String> form = parseForm(postedBody.get());
    assertEquals("0", form.get("DB"));
    assertEquals("641", form.get("JS_browser_height"));
    assertEquals("695", form.get("JS_browser_width"));
    assertEquals("1001", form.get("phone_login"));
    assertEquals("anexo_1001", form.get("phone_pass"));
    assertTrue(form.containsKey("LOGINvarONE"));
    assertTrue(form.containsKey("LOGINvarTWO"));
    assertTrue(form.containsKey("LOGINvarTHREE"));
    assertTrue(form.containsKey("LOGINvarFOUR"));
    assertTrue(form.containsKey("LOGINvarFIVE"));
    assertTrue(form.containsKey("hide_relogin_fields"));
    assertEquals("", form.get("LOGINvarONE"));
    assertEquals("", form.get("LOGINvarTWO"));
    assertEquals("", form.get("LOGINvarTHREE"));
    assertEquals("", form.get("LOGINvarFOUR"));
    assertEquals("", form.get("LOGINvarFIVE"));
    assertEquals("", form.get("hide_relogin_fields"));
    assertEquals("48373608", form.get("VD_login"));
    assertEquals("secret", form.get("VD_pass"));
    assertEquals("IVR", form.get("VD_campaign"));
  }

  @Test
  void detectsInvalidCredentialsFromBody() {
    VicidialClient client = new VicidialClient(mock(VicidialConfigService.class), 4000, 4000, 30);
    assertTrue(client.containsInvalidCredentials("ERROR: Invalid Username/Password"));
  }

  @Test
  void successHeuristicReturnsTrueWhenLogoutIsPresent() {
    VicidialClient client = new VicidialClient(mock(VicidialConfigService.class), 4000, 4000, 30);
    assertTrue(client.hasConnectSuccessSignals("<a>Logout</a>"));
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

    VicidialClient client = new VicidialClient(configService, 100, 100, 30);

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
