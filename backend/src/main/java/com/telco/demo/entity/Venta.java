package com.telco.demo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ventas", indexes = {@Index(columnList = "codigoLlamada", unique = true)})
public class Venta {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long agenteId;

    private String dniCliente;
    private String nombreCliente;
    private String telefonoCliente;
    private String direccionCliente;
    private String planActual;
    private String planNuevo;

    @Column(unique = true)
    private String codigoLlamada;
    private String producto;
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    private EstadoVenta estado = EstadoVenta.PENDIENTE;

    private String motivoRechazo;

    private Instant fechaRegistro = Instant.now();
    private Instant fechaValidacion;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getId(){return id;}
    public void setId(Long id){this.id=id;}
    public Long getAgenteId(){return agenteId;}
    public void setAgenteId(Long a){this.agenteId=a;}
    public String getDniCliente(){return dniCliente;}
    public void setDniCliente(String s){this.dniCliente=s;}
    public String getNombreCliente(){return nombreCliente;}
    public void setNombreCliente(String s){this.nombreCliente=s;}
    public String getTelefonoCliente(){return telefonoCliente;}
    public void setTelefonoCliente(String s){this.telefonoCliente=s;}
    public String getDireccionCliente(){return direccionCliente;}
    public void setDireccionCliente(String s){this.direccionCliente=s;}
    public String getPlanActual(){return planActual;}
    public void setPlanActual(String s){this.planActual=s;}
    public String getPlanNuevo(){return planNuevo;}
    public void setPlanNuevo(String s){this.planNuevo=s;}
    public String getCodigoLlamada(){return codigoLlamada;}
    public void setCodigoLlamada(String s){this.codigoLlamada=s;}
    public String getProducto(){return producto;}
    public void setProducto(String s){this.producto=s;}
    public java.math.BigDecimal getMonto(){return monto;}
    public void setMonto(BigDecimal m){this.monto=m;}
    public EstadoVenta getEstado(){return estado;}
    public void setEstado(EstadoVenta e){this.estado=e;}
    public String getMotivoRechazo(){return motivoRechazo;}
    public void setMotivoRechazo(String m){this.motivoRechazo=m;}
    public Instant getFechaRegistro(){return fechaRegistro;}
    public void setFechaRegistro(Instant i){this.fechaRegistro=i;}
    public Instant getFechaValidacion(){return fechaValidacion;}
    public void setFechaValidacion(Instant i){this.fechaValidacion=i;}
}
