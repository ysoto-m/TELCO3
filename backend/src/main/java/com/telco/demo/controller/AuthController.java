package com.telco.demo.controller;

import com.telco.demo.dto.AuthRequest;
import com.telco.demo.dto.AuthResponse;
import com.telco.demo.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){ this.auth = auth; }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest req){
        return ResponseEntity.ok(auth.authenticate(req));
    }
}
