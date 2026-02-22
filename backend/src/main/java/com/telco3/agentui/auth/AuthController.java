package com.telco3.agentui.auth;

import com.telco3.agentui.config.SecurityConfig.JwtService;
import com.telco3.agentui.domain.UserRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController @RequestMapping("/api/auth") @Validated
public class AuthController {
  private final UserRepository users; private final PasswordEncoder encoder; private final JwtService jwt;
  public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt){this.users=users;this.encoder=encoder;this.jwt=jwt;}
  public record LoginReq(@NotBlank String username,@NotBlank String password){}
  @PostMapping("/login")
  Map<String,Object> login(@RequestBody LoginReq req){
    var user=users.findByUsernameAndActiveTrue(req.username()).orElseThrow(()->new RuntimeException("Invalid credentials"));
    boolean valid = user.passwordHash.startsWith("{plain}") ? user.passwordHash.substring(7).equals(req.password()) : encoder.matches(req.password(), user.passwordHash);
    if(!valid) throw new RuntimeException("Invalid credentials");
    return Map.of("accessToken",jwt.generate(user.username,user.role.name()),"role",user.role.name(),"username",user.username);
  }
}
