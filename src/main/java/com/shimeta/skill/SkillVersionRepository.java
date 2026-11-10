package com.shimeta.skill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, String> {

    List<SkillVersion> findBySkillIdOrderByVersionDesc(String skillId);

    Optional<SkillVersion> findBySkillIdAndVersion(String skillId, Integer version);

    void deleteBySkillId(String skillId);
}
