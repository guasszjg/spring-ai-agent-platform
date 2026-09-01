package com.example.agentplatform.repository;

import com.example.agentplatform.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, String> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
}
