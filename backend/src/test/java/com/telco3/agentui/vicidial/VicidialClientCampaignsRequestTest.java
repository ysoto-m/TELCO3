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

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void campaignsForAgentUsesRealAgentCredentialsAndPhoneData() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/agc/api.php", exchange -> {
      receivedUri = exchange.getRequestURI();
      byte[] body = "campaign_id=CL_TEST".getBytes(StandardCharsets.UTF_8);
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

    VicidialClient client = new VicidialClient(configService);
    var result = client.campaignsForAgent("48373608", "agentPass123", "1001", "anexo_1001");

    Map<String, String> query = parseQuery(receivedUri.getRawQuery());
    assertEquals("48373608", query.get("user"));
    assertEquals("agentPass123", query.get("pass"));
    assertEquals("48373608", query.get("agent_user"));
    assertEquals("1001", query.get("phone_login"));
    assertEquals("anexo_1001", query.get("phone_pass"));
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
