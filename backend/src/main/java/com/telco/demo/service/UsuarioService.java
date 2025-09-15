package com.telco.demo.service;

import com.telco.demo.entity.Usuario;
import com.telco.demo.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class UsuarioService {
    private final UsuarioRepository repo;
    public UsuarioService(UsuarioRepository repo){ this.repo = repo; }
    public Optional<Usuario> findByUsername(String username){ return repo.findByUsername(username); }
    public Usuario save(Usuario u){ return repo.save(u); }
    public Optional<Usuario> findById(Long id){ return repo.findById(id); }
    public List<Usuario> findAllBySupervisorId(Long supervisorId){ return repo.findAllBySupervisorId(supervisorId); }
}
