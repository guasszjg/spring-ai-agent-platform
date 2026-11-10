package com.shimeta.skill;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<Skill, String> {

    List<Skill> findAllByOrderByUpdatedAtDesc();

    Optional<Skill> findByName(String name);

    boolean existsByName(String name);

    @Modifying
    @Transactional
    @Query("update Skill s set s.loadCount = s.loadCount + 1, s.lastLoadedAt = :at where s.id = :id")
    int recordLoad(@Param("id") String id, @Param("at") LocalDateTime at);
}
