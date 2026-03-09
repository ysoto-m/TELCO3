package com.telco3.agentui.vicidial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VicidialRealtimeStatusMapperTest {
  private final VicidialRealtimeStatusMapper mapper = new VicidialRealtimeStatusMapper();

  @Test
  void mapsReadyAndIncall() {
    assertEquals("Disponible", mapper.map("READY", null).visibleStatus());
    assertEquals("En llamada", mapper.map("INCALL", null).visibleStatus());
  }

  @Test
  void mapsPausedByPauseCode() {
    assertEquals("Break", mapper.map("PAUSED", "BREAK").visibleStatus());
    assertEquals("Baño", mapper.map("PAUSED", "BANO").visibleStatus());
    assertEquals("Pausa", mapper.map("PAUSED", "ANY").visibleStatus());
  }
}
