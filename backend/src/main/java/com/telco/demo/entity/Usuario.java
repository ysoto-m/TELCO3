package com.telco.demo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "usuarios")
public class Usuario {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role rol;

    private Long supervisorId;

    private Boolean activo = true;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getId(){return id;}
    public void setId(Long id){this.id=id;}
    public String getUsername(){return username;}
    public void setUsername(String u){this.username=u;}
    public String getPasswordHash(){return passwordHash;}
    public void setPasswordHash(String p){this.passwordHash=p;}
    public Role getRol(){return rol;}
    public void setRol(Role r){this.rol=r;}
    public Long getSupervisorId(){return supervisorId;}
    public void setSupervisorId(Long s){this.supervisorId=s;}
    public Boolean getActivo(){return activo;}
    public void setActivo(Boolean a){this.activo=a;}
    public Instant getCreatedAt(){return createdAt;}
    public void setCreatedAt(Instant i){this.createdAt=i;}
    public Instant getUpdatedAt(){return updatedAt;}
    public void setUpdatedAt(Instant i){this.updatedAt=i;}
}
