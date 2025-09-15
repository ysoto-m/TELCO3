package com.telco.demo.repository;

import com.telco.demo.entity.Venta;
import com.telco.demo.entity.EstadoVenta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface VentaRepository extends JpaRepository<Venta, Long> {
    Page<Venta> findByAgenteId(Long agenteId, Pageable p);
    Page<Venta> findByEstado(EstadoVenta estado, Pageable p);
    List<Venta> findByAgenteIdInAndFechaRegistroBetween(List<Long> agentes, Instant desde, Instant hasta);
    boolean existsByCodigoLlamada(String codigo);
}
