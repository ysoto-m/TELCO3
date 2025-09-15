package com.telco.demo.controller;

import com.telco.demo.dto.VentaDto;
import com.telco.demo.entity.Venta;
import com.telco.demo.service.VentaService;
import io.jsonwebtoken.Claims;
import com.telco.demo.util.JwtUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ventas")
public class VentaController {
    private final VentaService service;
    private final JwtUtil jwtUtil;
    public VentaController(VentaService service, JwtUtil jwtUtil){ this.service = service; this.jwtUtil = jwtUtil; }

    @PostMapping
    @PreAuthorize("hasRole('AGENTE') or hasRole('ADMIN')") // token must contain role claim
    public ResponseEntity<Venta> crear(@Valid @RequestBody VentaDto dto, @RequestHeader(name="Authorization") String auth){
        Long agenteId = extractUserId(auth);
        Venta v = service.create(agenteId, dto);
        return ResponseEntity.status(201).body(v);
    }

    @GetMapping("/mis-ventas")
    @PreAuthorize("hasRole('AGENTE') or hasRole('ADMIN')")
    public Page<Venta> misVentas(@RequestHeader(name="Authorization") String auth,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size){
        Long agenteId = extractUserId(auth);
        return service.listMisVentas(agenteId, PageRequest.of(page,size));
    }

    @GetMapping("/pendientes")
    @PreAuthorize("hasRole('BACKOFFICE') or hasRole('ADMIN')")
    public Page<Venta> pendientes(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size){
        return service.listPendientes(PageRequest.of(page,size));
    }

    @PostMapping("/{id}/aprobar")
    @PreAuthorize("hasRole('BACKOFFICE') or hasRole('ADMIN')")
    public ResponseEntity<?> aprobar(@PathVariable Long id){
        service.approve(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/rechazar")
    @PreAuthorize("hasRole('BACKOFFICE') or hasRole('ADMIN')")
    public ResponseEntity<?> rechazar(@PathVariable Long id, @RequestBody Map<String,String> body){
        String motivo = body.get("motivo");
        service.reject(id, motivo);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/equipo")
    @PreAuthorize("hasRole('SUPERVISOR') or hasRole('ADMIN')")
    public List<Venta> equipo(@RequestHeader(name="Authorization") String auth,
                              @RequestParam(required = false) String desde,
                              @RequestParam(required = false) String hasta){
        Long supId = extractUserId(auth);
        Instant d = (desde==null||desde.isBlank())? Instant.now().minus(30, ChronoUnit.DAYS) : Instant.parse(desde);
        Instant h = (hasta==null||hasta.isBlank())? Instant.now() : Instant.parse(hasta);
        return service.listEquipo(supId, d, h);
    }

    private Long extractUserId(String authHeader){
        if(authHeader==null || !authHeader.startsWith("Bearer ")) throw new RuntimeException("No auth header");
        String token = authHeader.substring(7);
        Claims c = jwtUtil.parse(token);
        Number id = c.get("userId", Number.class);
        return id.longValue();
    }
}
