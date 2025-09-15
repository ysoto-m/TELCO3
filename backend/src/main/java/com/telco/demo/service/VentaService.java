package com.telco.demo.service;

import com.telco.demo.dto.VentaDto;
import com.telco.demo.entity.EstadoVenta;
import com.telco.demo.entity.Venta;
import com.telco.demo.repository.VentaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VentaService {
    private final VentaRepository repo;
    private final UsuarioService usuarioService;

    public VentaService(VentaRepository repo, UsuarioService usuarioService){ this.repo = repo; this.usuarioService = usuarioService; }

    public Venta create(Long agenteId, VentaDto dto){
        if(repo.existsByCodigoLlamada(dto.codigoLlamada)) throw new RuntimeException("codigoLlamada ya existe");
        Venta v = new Venta();
        v.setAgenteId(agenteId);
        v.setDniCliente(dto.dniCliente);
        v.setNombreCliente(dto.nombreCliente);
        v.setTelefonoCliente(dto.telefonoCliente);
        v.setDireccionCliente(dto.direccionCliente);
        v.setPlanNuevo(dto.planNuevo);
        v.setCodigoLlamada(dto.codigoLlamada);
        v.setProducto(dto.producto);
        v.setMonto(dto.monto);
        v.setFechaRegistro(Instant.now());
        v.setEstado(EstadoVenta.PENDIENTE);
        return repo.save(v);
    }

    public Page<Venta> listMisVentas(Long agenteId, Pageable p){
        return repo.findByAgenteId(agenteId, p);
    }

    public Page<Venta> listPendientes(Pageable p){
        return repo.findByEstado(EstadoVenta.PENDIENTE, p);
    }

    public Venta approve(Long id){
        Venta v = repo.findById(id).orElseThrow(() -> new RuntimeException("Venta no encontrada"));
        if(v.getEstado() != EstadoVenta.PENDIENTE) throw new RuntimeException("Venta no está pendiente");
        v.setEstado(EstadoVenta.APROBADA);
        v.setFechaValidacion(Instant.now());
        return repo.save(v);
    }

    public Venta reject(Long id, String motivo){
        Venta v = repo.findById(id).orElseThrow(() -> new RuntimeException("Venta no encontrada"));
        if(v.getEstado() != EstadoVenta.PENDIENTE) throw new RuntimeException("Venta no está pendiente");
        v.setEstado(EstadoVenta.RECHAZADA);
        v.setMotivoRechazo(motivo);
        v.setFechaValidacion(Instant.now());
        return repo.save(v);
    }

    public List<Venta> listEquipo(Long supervisorId, Instant desde, Instant hasta){
        var agentes = usuarioService.findAllBySupervisorId(supervisorId);
        var ids = agentes.stream().map(a->a.getId()).collect(Collectors.toList());
        return repo.findByAgenteIdInAndFechaRegistroBetween(ids, desde, hasta);
    }
}
