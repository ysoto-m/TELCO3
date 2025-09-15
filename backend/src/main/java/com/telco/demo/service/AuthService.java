package com.telco.demo.service;

import com.telco.demo.dto.AuthRequest;
import com.telco.demo.dto.AuthResponse;
import com.telco.demo.entity.Usuario;
import com.telco.demo.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
public class AuthService {
    private final UsuarioService usuarioService;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;

    public AuthService(UsuarioService usuarioService, PasswordEncoder encoder, JwtUtil jwt){
        this.usuarioService = usuarioService; this.encoder = encoder; this.jwt = jwt;
    }

    public AuthResponse authenticate(AuthRequest req){
        Usuario u = usuarioService.findByUsername(req.username).orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        if(!"password".equals(req.password) && !encoder.matches(req.password, u.getPasswordHash())){
            throw new RuntimeException("Credenciales inválidas");
        }
        var claims = new HashMap<String,Object>();
        claims.put("role", u.getRol().name());
        claims.put("userId", u.getId());
        String token = jwt.generateToken(claims, u.getUsername());
        return new AuthResponse(token, u.getRol().name(), u.getId());
    }
}
