package com.example.agentplatform.rag;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.rag.engine.EngineType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeSourcePolicyTest {

    private static KnowledgeBase kb(String provider) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setProvider(provider);
        return kb;
    }

    @Test
    void sameSourceIsAccepted() {
        assertThat(KnowledgeSourcePolicy.requireSingleSource(List.of(kb("SPRING_AI"), kb("spring_ai"))))
                .isEqualTo(EngineType.SPRING_AI);
        assertThat(KnowledgeSourcePolicy.requireSingleSource(List.of(kb("DIFY")))).isEqualTo(EngineType.DIFY);
        assertThat(KnowledgeSourcePolicy.requireSingleSource(List.of())).isNull();
    }

    @Test
    void mixedSourcesAreRejected() {
        assertThatThrownBy(() -> KnowledgeSourcePolicy.requireSingleSource(List.of(kb("SPRING_AI"), kb("DIFY"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能使用一种知识来源");
    }

    @Test
    void legacyBlankProviderCountsAsDify() {
        assertThat(KnowledgeSourcePolicy.engineOf(kb(null))).isEqualTo(EngineType.DIFY);
        assertThat(KnowledgeSourcePolicy.engineOf(kb("SPRING_AI_NATIVE"))).isEqualTo(EngineType.DIFY);
    }
}
