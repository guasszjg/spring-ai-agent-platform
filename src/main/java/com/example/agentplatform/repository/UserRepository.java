package com.example.agentplatform.repository;

import com.example.agentplatform.model.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, String> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.role = 'SUPER_ADMIN' AND u.status IN ('ACTIVE', 'PENDING_PASSWORD')")
    List<AppUser> findActiveSuperAdminsForUpdate();
}
