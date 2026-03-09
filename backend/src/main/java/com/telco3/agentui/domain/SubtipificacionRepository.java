package com.telco3.agentui.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubtipificacionRepository extends JpaRepository<SubtipificacionEntity, Long> {
  List<SubtipificacionEntity> findByCampanaAndActivoOrderByNombreAsc(String campana, boolean activo);

  List<SubtipificacionEntity> findByCampanaAndTipificacionAndActivoOrderByNombreAsc(String campana, String tipificacion, boolean activo);

  List<SubtipificacionEntity> findByCampanaOrderByTipificacionAscNombreAsc(String campana);

  Optional<SubtipificacionEntity> findByCampanaAndCodigo(String campana, String codigo);
}
