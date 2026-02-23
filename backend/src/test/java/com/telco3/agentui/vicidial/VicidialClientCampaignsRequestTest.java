package com.telco3.agentui.vicidial;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VicidialClientCampaignsRequestTest {

  private HttpServer server;
  private URI receivedUri;
  private String receivedMethod;
  private String receivedBody;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void campaignsForAgentUsesVdcDbQueryLoginCampaignsFlow() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/agc/vdc_db_query.php", exchange -> {
      receivedUri = exchange.getRequestURI();
      receivedMethod = exchange.getRequestMethod();
      receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      byte[] body = "<select><option value=\"CL_TEST\">Test Campaign</option></select>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();

    VicidialConfigService configService = mock(VicidialConfigService.class);
    when(configService.resolve()).thenReturn(new VicidialConfigService.ResolvedVicidialConfig(
        "http://localhost:" + server.getAddress().getPort(),
        "apiuser",
        "apipass",
        "react_crm",
        false,
        "test"
    ));

    VicidialClient client = new VicidialClient(configService, 2000, 4000);
    var result = client.campaignsForAgent("48373608", "agentPass123");

    Map<String, String> form = parseQuery(receivedBody);
    assertEquals("POST", receivedMethod);
    assertEquals("/agc/vdc_db_query.php", receivedUri.getPath());
    assertEquals("48373608", form.get("user"));
    assertEquals("agentPass123", form.get("pass"));
    assertEquals("LogiNCamPaigns", form.get("ACTION"));
    assertEquals("html", form.get("format"));
    assertEquals(200, result.statusCode());
    assertTrue(result.body().contains("CL_TEST"));
  }

  private Map<String, String> parseQuery(String raw) {
    Map<String, String> map = new HashMap<>();
    if (raw == null || raw.isBlank()) {
      return map;
    }
    for (String token : raw.split("&")) {
      String[] parts = token.split("=", 2);
      if (parts.length == 2) {
        map.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
      }
    }
    return map;
  }
}
