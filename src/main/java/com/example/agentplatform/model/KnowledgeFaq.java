package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_faqs", indexes = {
    @Index(name = "idx_kfaq_kb_id", columnList = "knowledgeBaseId"),
    @Index(name = "idx_kfaq_category", columnList = "category")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeFaq {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    /**
     * 同步到外部 Dify 后的文档 ID（将问答作为检索文档同步）
     */
    @Column(length = 128)
    private String externalDocId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String answer;

    @Column(length = 64)
    private String category = "通用问答";

    /**
     * 内容形式：TEXT (纯文字), IMAGE (纯图), MIXED (图文混合)
     */
    @Column(length = 32)
    private String contentType = "TEXT";

    /**
     * 图片附件列表 (JSON Array 格式存储 URL)
     */
    @Column(columnDefinition = "TEXT")
    private String imageUrls;

    private Boolean enabled = true;

    private Long hitCount = 0L;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "faq-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.category == null || this.category.isBlank()) {
            this.category = "通用问答";
        }
        if (this.contentType == null || this.contentType.isBlank()) {
            this.contentType = "TEXT";
        }
        if (this.enabled == null) {
            this.enabled = true;
        }
        if (this.hitCount == null) {
            this.hitCount = 0L;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public KnowledgeFaq() {
    }

    public KnowledgeFaq(String knowledgeBaseId, String question, String answer, String category, String contentType) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.question = question;
        this.answer = answer;
        this.category = category;
        this.contentType = contentType;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getExternalDocId() {
        return externalDocId;
    }

    public void setExternalDocId(String externalDocId) {
        this.externalDocId = externalDocId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(String imageUrls) {
        this.imageUrls = imageUrls;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getHitCount() {
        return hitCount;
    }

    public void setHitCount(Long hitCount) {
        this.hitCount = hitCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
