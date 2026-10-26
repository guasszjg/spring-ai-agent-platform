package com.shimeta.repository;

import com.shimeta.model.GuardrailPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardrailPolicyRepository extends JpaRepository<GuardrailPolicy, String> {
}
