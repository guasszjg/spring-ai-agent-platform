package com.example.agentplatform.assistant;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocument;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 平台使用文档：助手回答"怎么用"类问题的依据（P2）。
 *
 * <p>jar 内置 {@code assistant/platform-guide.md}，启动后在后台导入名为「平台使用文档」的系统知识库（平台内置引擎）。
 * 内置文档内容变化（SHA-256 不同）时自动替换该文档；超级管理员在界面中向这个知识库追加的其他文档不受影响，同样可被检索。
 * 导入依赖已激活的向量模型：未配置时跳过，助手退回系统提示词中的静态功能介绍；之后首次检索时会再尝试导入。
 */
@Service
public class PlatformDocsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformDocsService.class);

    public static final String KB_NAME = "平台使用文档";
    static final String GUIDE_RESOURCE = "assistant/platform-guide.md";
    static final String GUIDE_FILE_NAME = "platform-guide.md";
    private static final String KB_DESCRIPTION = "AgentMatrix 平台使用文档（系统内置，供 AI 助手检索）。超级管理员可在此追加文档。";
    /** 以超级管理员身份导入：系统知识库只有超级管理员可以管理 */
    private static final CurrentActor SYSTEM = new CurrentActor("system", "system", UserRole.SUPER_ADMIN);
    private static final long RETRY_INTERVAL_MS = 5 * 60 * 1000L;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final EmbeddingConfigService embeddingConfigService;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "platform-docs-sync");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean ready;
    private volatile long lastAttemptAt;

    public PlatformDocsService(KnowledgeBaseService knowledgeBaseService,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               KnowledgeDocumentRepository documentRepository,
                               EmbeddingConfigService embeddingConfigService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.embeddingConfigService = embeddingConfigService;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        syncAsync();
    }

    public boolean isReady() {
        return ready;
    }

    /** 检索平台文档；尚未就绪时触发一次后台导入并返回空列表（调用方退回静态介绍）。 */
    public List<RetrievedChunk> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!ready) {
            if (System.currentTimeMillis() - lastAttemptAt > RETRY_INTERVAL_MS) {
                syncAsync();
            }
            return List.of();
        }
        Optional<KnowledgeBase> kb = findKnowledgeBase();
        if (kb.isEmpty()) {
            ready = false;
            return List.of();
        }
        try {
            return knowledgeBaseService.retrieveChunks(kb.get().getId(), query.trim(), topK);
        } catch (Exception e) {
            log.warn("平台文档检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    void syncAsync() {
        if (!syncing.compareAndSet(false, true)) {
            return;
        }
        lastAttemptAt = System.currentTimeMillis();
        try {
            executor.execute(() -> {
                CurrentActor.set(SYSTEM);
                try {
                    sync();
                } catch (Exception e) {
                    log.warn("平台使用文档导入失败（助手将使用内置的静态功能介绍）: {}", e.getMessage());
                } finally {
                    CurrentActor.clear();
                    syncing.set(false);
                }
            });
        } catch (Exception e) {
            syncing.set(false);
        }
    }

    /** 确保系统知识库存在且内置文档为最新版本。 */
    void sync() throws IOException {
        if (embeddingConfigService.getActiveConfig().isEmpty()) {
            log.info("未激活向量模型，跳过平台使用文档导入；配置向量模型后助手首次检索时会自动导入");
            ready = false;
            return;
        }
        byte[] guide = readGuide();
        String sha256 = sha256(guide);

        KnowledgeBase kb = findKnowledgeBase().orElseGet(this::createKnowledgeBase);
        List<KnowledgeDocument> existing = documentRepository.findByKnowledgeBaseId(kb.getId()).stream()
                .filter(doc -> GUIDE_FILE_NAME.equals(doc.getName()))
                .toList();
        if (existing.size() == 1 && sha256.equalsIgnoreCase(existing.get(0).getSha256())) {
            ready = true;
            return;
        }
        for (KnowledgeDocument old : existing) {
            knowledgeBaseService.deleteDocument(kb.getId(), old.getId(), SYSTEM);
        }
        knowledgeBaseService.uploadDocuments(kb.getId(), List.of(new BytesMultipartFile(GUIDE_FILE_NAME, guide)), SYSTEM);
        ready = true;
        log.info("平台使用文档已导入知识库「{}」（sha256={}）", KB_NAME, sha256.substring(0, 12));
    }

    Optional<KnowledgeBase> findKnowledgeBase() {
        return knowledgeBaseRepository.findAll().stream()
                .filter(kb -> KB_NAME.equals(kb.getName())
                        && Boolean.TRUE.equals(kb.getIsSystem())
                        && "SPRING_AI".equalsIgnoreCase(kb.getProvider()))
                .findFirst();
    }

    private KnowledgeBase createKnowledgeBase() {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(KB_NAME);
        req.setDescription(KB_DESCRIPTION);
        req.setProvider("SPRING_AI");
        req.setAvatar("📘");
        req.setTopK(5);
        req.setScoreThreshold(0.3);
        // actor 为空：以系统身份创建系统公共知识库（全员可见、仅超级管理员可管理）
        return knowledgeBaseService.createKnowledgeBase(req, null);
    }

    static byte[] readGuide() throws IOException {
        try (InputStream in = new ClassPathResource(GUIDE_RESOURCE).getInputStream()) {
            return in.readAllBytes();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("缺少 SHA-256 摘要算法", e);
        }
    }

    /** 内存中的上传文件，复用知识库的上传与切片流程。 */
    static final class BytesMultipartFile implements MultipartFile {
        private final String name;
        private final byte[] bytes;

        BytesMultipartFile(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return "text/markdown"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
