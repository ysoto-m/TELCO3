package com.telco.demo.dto;
public class AuthResponse {
    public String token;
    public String rol;
    public Long userId;
    public AuthResponse(String token, String rol, Long userId){this.token=token;this.rol=rol;this.userId=userId;}
}
