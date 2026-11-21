package com.shimeta.assistant;

import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocument;
import com.shimeta.model.UserRole;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.dto.CreateKnowledgeBaseRequest;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.repository.KnowledgeDocumentRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.EmbeddingConfigService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.storage.ObjectStorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 平台使用文档：助手回答"怎么用""报错怎么办"类问题的依据（P2 / 4.2 多文档增量同步）。
 *
 * <p>jar 内置一组平台文档（使用文档、功能说明、开放接口指南、报错字典、部署运维），启动后在后台导入名为
 * 「平台使用文档」的系统知识库（平台内置引擎）。每篇文档单独按 SHA-256（及本机原件归档是否存在）判断是否需要重新导入，
 * 哪篇变动只替换哪篇；超级管理员在界面中追加的其他文档不受影响。
 * 运维文档（{@code deploy-ops.md}）仅超级管理员检索可见。
 */
@Service
public class PlatformDocsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformDocsService.class);

    public static final String KB_NAME = "平台使用文档";
    static final String GUIDE_RESOURCE = "assistant/platform-guide.md";
    static final String GUIDE_FILE_NAME = "platform-guide.md";
    static final String OPS_FILE_NAME = "deploy-ops.md";

    /** 内置文档集合：每篇单独做 SHA-256 增量更新；superAdminOnly=true 的文档仅超级管理员可检索。 */
    static final List<DocSpec> BUNDLED_DOCS = List.of(
            new DocSpec(GUIDE_FILE_NAME, GUIDE_RESOURCE, false),
            new DocSpec("platform-features.md", "assistant/docs/platform-features.md", false),
            new DocSpec("open-api-guide.md", "assistant/docs/open-api-guide.md", false),
            new DocSpec("error-dictionary.md", "assistant/docs/error-dictionary.md", false),
            new DocSpec(OPS_FILE_NAME, "assistant/docs/deploy-ops.md", true)
    );
    private static final Set<String> SUPER_ADMIN_ONLY_FILES = BUNDLED_DOCS.stream()
            .filter(DocSpec::superAdminOnly)
            .map(DocSpec::fileName)
            .collect(Collectors.toUnmodifiableSet());

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
    private ObjectStorageService objectStorageService;

    /** 可选注入：用于检查原件归档是否在本机存在。 */
    @Autowired(required = false)
    public void setObjectStorageService(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
    }

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

    public static List<DocSpec> bundledDocs() {
        return BUNDLED_DOCS;
    }

    /** 检索平台文档（默认不返回仅超级管理员可见的运维文档）。 */
    public List<RetrievedChunk> search(String query, int topK) {
        return search(query, topK, null);
    }

    /** 按用户角色检索平台文档；运维文档仅对超级管理员返回。尚未就绪时触发一次后台导入并返回空列表。 */
    public List<RetrievedChunk> search(String query, int topK, CurrentActor actor) {
        if (query == null || query.isBlank() || topK <= 0) {
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
        boolean allowAdminDocs = actor != null && actor.isSuperAdmin();
        int fetchK = allowAdminDocs ? topK : Math.max(topK + 4, topK * 2);
        try {
            List<RetrievedChunk> chunks = knowledgeBaseService.retrieveChunks(kb.get().getId(), query.trim(), fetchK);
            if (allowAdminDocs) {
                return chunks.size() <= topK ? chunks : chunks.subList(0, topK);
            }
            return chunks.stream()
                    .filter(chunk -> !isSuperAdminOnlySource(chunk.sourceName()))
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("平台文档检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    static boolean isSuperAdminOnlySource(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return false;
        }
        String trimmed = sourceName.trim();
        for (String restricted : SUPER_ADMIN_ONLY_FILES) {
            if (restricted.equalsIgnoreCase(trimmed) || trimmed.endsWith("/" + restricted)
                    || trimmed.startsWith(restricted + " ")) {
                return true;
            }
        }
        return false;
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

    /** 确保系统知识库存在且每篇内置文档均为最新版本（按文档单独 SHA-256 增量更新）。 */
    void sync() throws IOException {
        if (embeddingConfigService.getActiveConfig().isEmpty()) {
            log.info("未激活向量模型，跳过平台使用文档导入；配置向量模型后助手首次检索时会自动导入");
            ready = false;
            return;
        }

        KnowledgeBase kb = findKnowledgeBase().orElseGet(this::createKnowledgeBase);
        List<KnowledgeDocument> allExisting = documentRepository.findByKnowledgeBaseId(kb.getId());

        int updatedCount = 0;
        for (DocSpec spec : BUNDLED_DOCS) {
            byte[] bytes = readDoc(spec);
            String sha256 = sha256(bytes);
            List<KnowledgeDocument> existing = allExisting.stream()
                    .filter(doc -> spec.fileName().equals(doc.getName()))
                    .toList();
            if (existing.size() == 1 && sha256.equalsIgnoreCase(existing.get(0).getSha256())
                    && archiveExists(existing.get(0))) {
                continue;
            }
            for (KnowledgeDocument old : existing) {
                knowledgeBaseService.deleteDocument(kb.getId(), old.getId(), SYSTEM);
            }
            knowledgeBaseService.uploadDocuments(kb.getId(),
                    List.of(new BytesMultipartFile(spec.fileName(), bytes)), SYSTEM);
            updatedCount++;
            log.info("平台内置文档「{}」已同步至知识库「{}」（sha256={}）",
                    spec.fileName(), KB_NAME, sha256.substring(0, 12));
        }
        ready = true;
        if (updatedCount == 0) {
            log.debug("平台内置文档全部为最新版本，跳过重复导入（共 {} 篇）", BUNDLED_DOCS.size());
        }
    }

    /**
     * 原件归档是否在当前机器的对象存储中。归档存在运行程序那台机器上（默认 ./data/kb-files），
     * 换一台机器或换一个工作目录启动时会缺失：此时重新导入，让归档与检索数据一起补齐。
     * 未配置对象存储时不检查。
     */
    private boolean archiveExists(KnowledgeDocument doc) {
        if (objectStorageService == null) {
            return true;
        }
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            return false;
        }
        try {
            boolean exists = objectStorageService.exists(doc.getObjectKey());
            if (!exists) {
                log.info("平台使用文档「{}」的原件归档在本机不存在（{}），将重新导入", doc.getName(), doc.getObjectKey());
            }
            return exists;
        } catch (Exception e) {
            log.debug("检查平台使用文档归档失败: {}", e.getMessage());
            return true;
        }
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
        return readDoc(BUNDLED_DOCS.get(0));
    }

    static byte[] readDoc(DocSpec spec) throws IOException {
        try (InputStream in = new ClassPathResource(spec.resourcePath()).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** 读取全部内置文档内容（fileName -> UTF-8 正文），供单测校验 docAnchor 与覆盖范围。 */
    static Map<String, String> readAllBundledDocs() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (DocSpec spec : BUNDLED_DOCS) {
            result.put(spec.fileName(), new String(readDoc(spec), StandardCharsets.UTF_8));
        }
        return result;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("缺少 SHA-256 摘要算法", e);
        }
    }

    public record DocSpec(String fileName, String resourcePath, boolean superAdminOnly) {
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
