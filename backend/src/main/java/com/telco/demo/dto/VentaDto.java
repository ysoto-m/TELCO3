package com.telco.demo.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
public class VentaDto {
    @NotBlank
    @Pattern(regexp = "^\\d{8}$|^\\d{11}$")
    public String dniCliente;

    @NotBlank
    public String nombreCliente;

    @NotBlank
    @Pattern(regexp = "^\\d{9}$")
    public String telefonoCliente;

    @NotBlank
    public String direccionCliente;

    @NotBlank
    public String codigoLlamada;

    @NotBlank
    public String planNuevo;

    @NotBlank
    public String producto;

    @NotNull
    public BigDecimal monto;
}
