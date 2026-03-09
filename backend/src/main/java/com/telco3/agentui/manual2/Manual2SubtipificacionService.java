package com.telco3.agentui.manual2;

import com.telco3.agentui.campaign.core.CampaignInteractionCoreService;
import com.telco3.agentui.domain.SubtipificacionEntity;
import com.telco3.agentui.domain.SubtipificacionRepository;
import com.telco3.agentui.vicidial.VicidialServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class Manual2SubtipificacionService {
  private final SubtipificacionRepository subtipificacionRepository;
  private final CampaignInteractionCoreService coreService;

  public Manual2SubtipificacionService(
      SubtipificacionRepository subtipificacionRepository,
      CampaignInteractionCoreService coreService
  ) {
    this.subtipificacionRepository = subtipificacionRepository;
    this.coreService = coreService;
  }

  public List<Map<String, Object>> listSubtipificaciones(String campaignId, String tipificacion) {
    String campaign = coreService.firstNonBlank(campaignId, "Manual2");
    List<SubtipificacionEntity> rows;
    if (StringUtils.hasText(tipificacion)) {
      rows = subtipificacionRepository.findByCampanaAndTipificacionAndActivoOrderByNombreAsc(
          campaign,
          tipificacion.trim().toUpperCase(Locale.ROOT),
          true
      );
    } else {
      rows = subtipificacionRepository.findByCampanaAndActivoOrderByNombreAsc(campaign, true);
    }
    return rows.stream().map(this::toSubtipificacionItem).toList();
  }

  public List<Map<String, Object>> listAdminSubtipificaciones(String campaignId) {
    String campaign = coreService.firstNonBlank(campaignId, "Manual2");
    return subtipificacionRepository.findByCampanaOrderByTipificacionAscNombreAsc(campaign).stream()
        .map(this::toSubtipificacionItem)
        .toList();
  }

  @Transactional
  public Map<String, Object> createOrUpdateSubtipificacion(String adminUser, Manual2Service.UpsertSubtipificacionRequest request) {
    String campaign = coreService.firstNonBlank(request.campana(), "Manual2");
    String codigo = coreService.firstNonBlank(request.codigo());
    String nombre = coreService.firstNonBlank(request.nombre());
    String tipificacion = coreService.firstNonBlank(request.tipificacion());

    if (!StringUtils.hasText(codigo) || !StringUtils.hasText(nombre) || !StringUtils.hasText(tipificacion)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "MANUAL2_SUBTIP_REQUIRED",
          "Codigo, nombre y tipificacion son obligatorios.",
          "Complete todos los campos requeridos.",
          null
      );
    }

    SubtipificacionEntity entity = subtipificacionRepository.findByCampanaAndCodigo(campaign, codigo)
        .orElseGet(SubtipificacionEntity::new);
    entity.campana = campaign;
    entity.codigo = codigo.trim().toUpperCase(Locale.ROOT);
    entity.nombre = nombre.trim();
    entity.tipificacion = tipificacion.trim().toUpperCase(Locale.ROOT);
    entity.activo = request.activo() == null || request.activo();
    if (entity.fechaCreacion == null) {
      entity.fechaCreacion = coreService.nowLima();
    }
    subtipificacionRepository.save(entity);

    return Map.of(
        "ok", true,
        "item", toSubtipificacionItem(entity),
        "updatedBy", Objects.toString(adminUser, "")
    );
  }

  @Transactional
  public Map<String, Object> setSubtipificacionActivo(String adminUser, String campaignId, String codigo, boolean activo) {
    String campaign = coreService.firstNonBlank(campaignId, "Manual2");
    String normalizedCode = coreService.firstNonBlank(codigo);
    if (!StringUtils.hasText(normalizedCode)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "MANUAL2_SUBTIP_CODE_REQUIRED",
          "El codigo de subtipificacion es obligatorio.",
          "Envie un codigo valido.",
          null
      );
    }

    SubtipificacionEntity entity = subtipificacionRepository.findByCampanaAndCodigo(campaign, normalizedCode)
        .orElseThrow(() -> new VicidialServiceException(
            HttpStatus.NOT_FOUND,
            "MANUAL2_SUBTIP_NOT_FOUND",
            "No se encontro la subtipificacion solicitada.",
            "Verifique campana y codigo.",
            Map.of("campana", campaign, "codigo", normalizedCode)
        ));
    entity.activo = activo;
    subtipificacionRepository.save(entity);

    return Map.of(
        "ok", true,
        "item", toSubtipificacionItem(entity),
        "updatedBy", Objects.toString(adminUser, "")
    );
  }

  private Map<String, Object> toSubtipificacionItem(SubtipificacionEntity entity) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", entity.id);
    item.put("campana", Objects.toString(entity.campana, ""));
    item.put("codigo", Objects.toString(entity.codigo, ""));
    item.put("nombre", Objects.toString(entity.nombre, ""));
    item.put("tipificacion", Objects.toString(entity.tipificacion, ""));
    item.put("activo", entity.activo);
    item.put("fechaCreacion", coreService.formatLocal(entity.fechaCreacion));
    return item;
  }
}
