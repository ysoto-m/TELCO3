package com.telco3.agentui.settings;

import com.telco3.agentui.domain.Entities.VicidialSettingsEntity;
import com.telco3.agentui.domain.SettingsRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

@RestController @RequestMapping("/api/settings")
public class SettingsController {
  private final SettingsRepository repo;
  private final SecretKeySpec key;
  public SettingsController(SettingsRepository repo,@Value("${app.crypto-key}") String k){this.repo=repo;this.key=new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8),"AES");}
  public record SettingsReq(@NotBlank String baseUrl,@NotBlank String apiUser,@NotBlank String apiPass,@NotBlank String source){}

  @GetMapping("/vicidial")
  public Map<String,Object> get(){
    var s=repo.findById(1L).orElse(new VicidialSettingsEntity());
    return Map.of("baseUrl",n(s.baseUrl),"apiUser",n(s.apiUser),"source",n(s.source),"updatedAt",s.updatedAt==null?OffsetDateTime.now():s.updatedAt);
  }
  @PutMapping("/vicidial")
  public Map<String,Object> put(@RequestBody SettingsReq req) throws Exception {
    var s=repo.findById(1L).orElse(new VicidialSettingsEntity());
    s.id=1L; s.baseUrl=req.baseUrl(); s.apiUser=req.apiUser(); s.apiPassEncrypted=encrypt(req.apiPass()); s.source=req.source(); s.updatedAt=OffsetDateTime.now();
    repo.save(s); return Map.of("ok",true);
  }
  public String decryptedPass() {
    try { var s=repo.findById(1L).orElseThrow(); return decrypt(s.apiPassEncrypted);} catch(Exception e){ throw new RuntimeException(e);}
  }
  public VicidialSettingsEntity current(){ return repo.findById(1L).orElseThrow(); }
  private String encrypt(String p) throws Exception { var c=Cipher.getInstance("AES"); c.init(Cipher.ENCRYPT_MODE,key); return Base64.getEncoder().encodeToString(c.doFinal(p.getBytes(StandardCharsets.UTF_8))); }
  private String decrypt(String p) throws Exception { var c=Cipher.getInstance("AES"); c.init(Cipher.DECRYPT_MODE,key); return new String(c.doFinal(Base64.getDecoder().decode(p)),StandardCharsets.UTF_8); }
  private String n(String s){return s==null?"":s;}
}
