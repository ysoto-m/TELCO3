package com.telco3.agentui.manual2;

import com.telco3.agentui.campaign.core.CampaignInteractionCoreService;
import com.telco3.agentui.domain.ContactoEntity;
import com.telco3.agentui.domain.ContactoRepository;
import com.telco3.agentui.domain.GestionLlamadaEntity;
import com.telco3.agentui.domain.GestionLlamadaRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class Manual2ContactoService {
  private final ContactoRepository contactoRepository;
  private final GestionLlamadaRepository gestionLlamadaRepository;
  private final CampaignInteractionCoreService coreService;

  public Manual2ContactoService(
      ContactoRepository contactoRepository,
      GestionLlamadaRepository gestionLlamadaRepository,
      CampaignInteractionCoreService coreService
  ) {
    this.contactoRepository = contactoRepository;
    this.gestionLlamadaRepository = gestionLlamadaRepository;
    this.coreService = coreService;
  }

  public Map<String, Object> lookupContacto(String phoneNumber) {
    String telefono = coreService.normalizePhone(phoneNumber);
    if (!StringUtils.hasText(telefono)) {
      return Map.of("found", false, "telefono", "");
    }

    var contactoOpt = contactoRepository.findByTelefono(telefono);
    long totalGestiones = gestionLlamadaRepository.countByTelefono(telefono);
    List<Map<String, Object>> historial = gestionLlamadaRepository.findTop100ByTelefonoOrderByFechaGestionDesc(telefono).stream()
        .limit(20)
        .map(this::toGestionItem)
        .toList();

    if (contactoOpt.isEmpty()) {
      return Map.of(
          "found", false,
          "telefono", telefono,
          "totalGestiones", totalGestiones,
          "historial", historial
      );
    }

    ContactoEntity contacto = contactoOpt.get();
    return Map.of(
        "found", true,
        "telefono", telefono,
        "contacto", Map.of(
            "id", contacto.id,
            "telefono", contacto.telefono,
            "nombres", Objects.toString(contacto.nombres, ""),
            "apellidos", Objects.toString(contacto.apellidos, ""),
            "documento", Objects.toString(contacto.documento, ""),
            "origen", Objects.toString(contacto.origen, ""),
            "fechaCreacion", coreService.formatLocal(contacto.fechaCreacion)
        ),
        "totalGestiones", totalGestiones,
        "historial", historial
    );
  }

  public Map<String, Object> historyByTelefono(String phoneNumber) {
    String telefono = coreService.normalizePhone(phoneNumber);
    if (!StringUtils.hasText(telefono)) {
      return Map.of("items", List.of(), "total", 0, "telefono", "");
    }

    var contacto = contactoRepository.findByTelefono(telefono).orElse(null);
    var rows = gestionLlamadaRepository.findTop100ByTelefonoOrderByFechaGestionDesc(telefono);
    List<Map<String, Object>> items = rows.stream().map(this::toGestionItem).toList();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("telefono", telefono);
    response.put("total", rows.size());
    if (contacto != null) {
      response.put("contacto", Map.of(
          "id", contacto.id,
          "nombres", Objects.toString(contacto.nombres, ""),
          "apellidos", Objects.toString(contacto.apellidos, ""),
          "documento", Objects.toString(contacto.documento, "")
      ));
    } else {
      response.put("contacto", null);
    }
    response.put("items", items);
    return response;
  }

  private Map<String, Object> toGestionItem(GestionLlamadaEntity gestion) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", gestion.id);
    item.put("fechaGestion", coreService.formatLocal(gestion.fechaGestion));
    item.put("agente", gestion.agente);
    item.put("campana", gestion.campana);
    item.put("telefono", gestion.telefono);
    item.put("tipificacion", coreService.firstNonBlank(gestion.tipificacion, gestion.disposicion));
    item.put("disposicion", gestion.disposicion);
    item.put("subtipificacion", gestion.subtipificacion);
    item.put("observaciones", Objects.toString(gestion.observaciones, ""));
    item.put("leadId", gestion.leadId);
    item.put("callId", gestion.callId);
    item.put("uniqueId", gestion.uniqueId);
    item.put("nombreAudio", gestion.nombreAudio);
    item.put("duracion", gestion.duracion);
    item.put("interaccionId", gestion.interaccionId);
    return item;
  }
}
