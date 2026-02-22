package com.telco3.agentui.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder(){ return new BCryptPasswordEncoder(); }

  @Bean SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
    return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a->a.requestMatchers("/api/auth/login","/swagger-ui/**","/v3/api-docs/**","/actuator/health").permitAll()
        .requestMatchers("/api/settings/**","/api/vicidial/**","/api/reports/**").hasRole("REPORT_ADMIN")
        .anyRequest().authenticated())
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
  }

  @Component
  public static class JwtService {
    private final SecretKey key;
    public JwtService(@Value("${app.jwt-secret}") String secret){ this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }
    public String generate(String username,String role){
      return Jwts.builder().subject(username).claim("role", role).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+86400000)).signWith(key).compact();
    }
    public Jws<Claims> parse(String token){ return Jwts.parser().verifyWith(key).build().parseSignedClaims(token); }
  }

  @Component
  public static class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    public JwtFilter(JwtService jwt){this.jwt=jwt;}
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
      String h=req.getHeader(HttpHeaders.AUTHORIZATION);
      if(h!=null && h.startsWith("Bearer ")){
        try {
          Claims c=jwt.parse(h.substring(7)).getPayload();
          var auth=new UsernamePasswordAuthenticationToken(c.getSubject(),null, List.of(new SimpleGrantedAuthority("ROLE_"+c.get("role",String.class))));
          org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception ignored) {}
      }
      chain.doFilter(req,res);
    }
  }
}
