package com.shimeta.service;

import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocument;
import com.shimeta.rag.SpringAiKnowledgeBaseProvider;
import com.shimeta.repository.KnowledgeDocumentRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 重建自建知识库的索引：用上传时保存的原件，逐个文档重新解析、切片、向量化，替换原来的切片。
 * 解析或切片方式升级后（例如 3.2 给切片加上下文标题）让已有文档生效。后台逐个处理，页面轮询进度。
 */
@Service
public class KnowledgeIndexRebuildService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexRebuildService.class);
    private static final int MAX_ERRORS = 20;

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    /** 重建进度。total 是要处理的文档数，done 是已处理（含失败和跳过） */
    public record Status(String state, int total, int done, int rebuilt, int skipped, int failed, int chunks,
                         String currentDocument, List<String> errors,
                         LocalDateTime startedAt, LocalDateTime finishedAt) {
        static Status idle() {
            return new Status("IDLE", 0, 0, 0, 0, 0, 0, null, List.of(), null, null);
        }
    }

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentRepository documentRepository;
    private final ObjectStorageService objectStorageService;
    private final SpringAiKnowledgeBaseProvider springAiProvider;
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kb-index-rebuild");
        t.setDaemon(true);
        return t;
    });

    public KnowledgeIndexRebuildService(KnowledgeBaseService knowledgeBaseService,
                                        KnowledgeDocumentRepository documentRepository,
                                        ObjectStorageService objectStorageService,
                                        SpringAiKnowledgeBaseProvider springAiProvider) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentRepository = documentRepository;
        this.objectStorageService = objectStorageService;
        this.springAiProvider = springAiProvider;
    }

    public Status status(String kbId, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        return statuses.getOrDefault(kbId, Status.idle());
    }

    /** 开始重建；已经在重建时直接返回当前进度 */
    public synchronized Status start(String kbId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseService.requireManageable(kbId, actor);
        if (!"SPRING_AI".equalsIgnoreCase(kb.getProvider())) {
            throw new IllegalArgumentException("只有自建知识库可以重建索引，Dify 知识库由 Dify 处理");
        }
        Status current = statuses.get(kbId);
        if (current != null && RUNNING.equals(current.state())) {
            return current;
        }
        List<KnowledgeDocument> docs = new ArrayList<>(documentRepository.findByKnowledgeBaseId(kbId));
        docs.sort(Comparator.comparing(KnowledgeDocument::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        Status started = new Status(RUNNING, docs.size(), 0, 0, 0, 0, 0, null, List.of(), LocalDateTime.now(), null);
        statuses.put(kbId, started);
        executor.submit(() -> run(kb, docs));
        return started;
    }

    private void run(KnowledgeBase kb, List<KnowledgeDocument> docs) {
        String kbId = kb.getId();
        int done = 0;
        int rebuilt = 0;
        int skipped = 0;
        int failed = 0;
        int chunks = 0;
        List<String> errors = new ArrayList<>();
        LocalDateTime startedAt = statuses.get(kbId).startedAt();
        for (KnowledgeDocument doc : docs) {
            statuses.put(kbId, new Status(RUNNING, docs.size(), done, rebuilt, skipped, failed, chunks, doc.getName(),
                    List.copyOf(errors), startedAt, null));
            try {
                if (doc.getExternalDocId() == null || doc.getExternalDocId().isBlank()) {
                    skipped++;
                    addError(errors, doc.getName() + "：没有切片记录，已跳过");
                } else if (doc.getObjectKey() == null || doc.getObjectKey().isBlank() || !objectStorageService.exists(doc.getObjectKey())) {
                    skipped++;
                    addError(errors, doc.getName() + "：找不到上传时保存的原件，已跳过（可删除后重新上传）");
                } else {
                    byte[] bytes;
                    try (InputStream in = objectStorageService.getObject(doc.getObjectKey())) {
                        bytes = in.readAllBytes();
                    }
                    chunks += springAiProvider.rebuildDocument(kb.getExternalDatasetId(), doc.getExternalDocId(), doc.getName(), bytes);
                    rebuilt++;
                }
            } catch (Exception e) {
                failed++;
                addError(errors, doc.getName() + "：" + e.getMessage());
                log.warn("重建文档索引失败 kb={} doc={}: {}", kbId, doc.getId(), e.getMessage());
            }
            done++;
        }
        String state = failed > 0 && rebuilt == 0 ? FAILED : DONE;
        statuses.put(kbId, new Status(state, docs.size(), done, rebuilt, skipped, failed, chunks, null,
                List.copyOf(errors), startedAt, LocalDateTime.now()));
        log.info("知识库索引重建完成 kb={}: 重建 {} 个文档，跳过 {}，失败 {}，共 {} 个切片", kbId, rebuilt, skipped, failed, chunks);
    }

    private static void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }
}
