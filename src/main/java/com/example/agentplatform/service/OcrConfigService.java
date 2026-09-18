package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.OcrConfig;
import com.example.agentplatform.model.OcrConfigRequest;
import com.example.agentplatform.model.OcrConfigView;
import com.example.agentplatform.rag.parser.ResolvedOcrSettings;
import com.example.agentplatform.repository.OcrConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OcrConfigService {

    private static final Logger log = LoggerFactory.getLogger(OcrConfigService.class);

    private final OcrConfigRepository repository;
    private final SecretCrypto secretCrypto;

    public OcrConfigService(OcrConfigRepository repository, SecretCrypto secretCrypto) {
        this.repository = repository;
        this.secretCrypto = secretCrypto;
    }

    public List<OcrConfigView> listViews() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(e -> OcrConfigView.from(e, decryptKey(e.getApiKeyEncrypted())))
                .collect(Collectors.toList());
    }

    /**
     * 只认网关入库且激活的 OCR。没有激活项则关闭，不读配置文件，避免 Key/地址进仓库。
     */
    public ResolvedOcrSettings resolve() {
        Optional<OcrConfig> active = repository.findFirstByIsActiveTrue();
        if (active.isEmpty()) {
            return new ResolvedOcrSettings("LOCAL_PADDLE_OCR", false, "", "", "", 30);
        }
        OcrConfig cfg = active.get();
        boolean enabled = Boolean.TRUE.equals(cfg.getEnabled());
        String provider = normalizeProvider(cfg.getProvider());
        int timeout = cfg.getTimeoutSeconds() != null && cfg.getTimeoutSeconds() > 0
                ? cfg.getTimeoutSeconds() : (ResolvedOcrSettings.isOnlineProvider(provider) ? 60 : 30);
        return new ResolvedOcrSettings(
                provider,
                enabled,
                cfg.getEndpoint(),
                decryptKey(cfg.getApiKeyEncrypted()),
                cfg.getModelName(),
                timeout
        );
    }

    @Transactional
    public OcrConfigView create(OcrConfigRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("配置名称不能为空");
        }
        if (req.getEndpoint() == null || req.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("OCR 接口地址不能为空");
        }
        String provider = normalizeProvider(req.getProvider());
        if (ResolvedOcrSettings.isOnlineProvider(provider)
                && (req.getApiKey() == null || req.getApiKey().isBlank())) {
            throw new IllegalArgumentException("在线 OCR 必须填写 API Key");
        }

        OcrConfig entity = new OcrConfig();
        entity.setName(req.getName().trim());
        entity.setProvider(provider);
        entity.setEndpoint(normalizeUrl(req.getEndpoint()));
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        entity.setModelName(req.getModelName() != null ? req.getModelName().trim() : null);
        entity.setTimeoutSeconds(req.getTimeoutSeconds() != null && req.getTimeoutSeconds() > 0
                ? req.getTimeoutSeconds() : (ResolvedOcrSettings.isOnlineProvider(provider) ? 60 : 30));
        entity.setEnabled(req.getEnabled() == null || req.getEnabled());

        boolean makeActive = Boolean.TRUE.equals(req.getIsActive()) || repository.count() == 0;
        if (makeActive) {
            repository.deactivateAll();
            entity.setIsActive(true);
        } else {
            entity.setIsActive(false);
        }
        OcrConfig saved = repository.save(entity);
        return OcrConfigView.from(saved, decryptKey(saved.getApiKeyEncrypted()));
    }

    @Transactional
    public OcrConfigView update(String id, OcrConfigRequest req) {
        OcrConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 OCR 配置: " + id));
        if (req.getName() != null && !req.getName().isBlank()) {
            entity.setName(req.getName().trim());
        }
        if (req.getProvider() != null && !req.getProvider().isBlank()) {
            entity.setProvider(normalizeProvider(req.getProvider()));
        }
        if (req.getEndpoint() != null && !req.getEndpoint().isBlank()) {
            entity.setEndpoint(normalizeUrl(req.getEndpoint()));
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        if (req.getModelName() != null) {
            entity.setModelName(req.getModelName().trim());
        }
        if (req.getTimeoutSeconds() != null && req.getTimeoutSeconds() > 0) {
            entity.setTimeoutSeconds(req.getTimeoutSeconds());
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        if (Boolean.TRUE.equals(req.getIsActive())) {
            repository.deactivateOthers(id);
            entity.setIsActive(true);
        }
        OcrConfig saved = repository.save(entity);
        return OcrConfigView.from(saved, decryptKey(saved.getApiKeyEncrypted()));
    }

    @Transactional
    public void activate(String id) {
        OcrConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 OCR 配置: " + id));
        repository.deactivateOthers(id);
        entity.setIsActive(true);
        entity.setEnabled(true);
        repository.save(entity);
    }

    @Transactional
    public void delete(String id) {
        OcrConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 OCR 配置: " + id));
        boolean wasActive = Boolean.TRUE.equals(entity.getIsActive());
        repository.delete(entity);
        if (wasActive) {
            repository.findAllByOrderByCreatedAtDesc().stream().findFirst().ifPresent(first -> {
                first.setIsActive(true);
                repository.save(first);
            });
        }
    }

    public Map<String, Object> probe(String id) {
        OcrConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 OCR 配置: " + id));
        Map<String, Object> result = ping(
                entity.getEndpoint(),
                decryptKey(entity.getApiKeyEncrypted()),
                ResolvedOcrSettings.isOnlineProvider(entity.getProvider()),
                entity.getTimeoutSeconds()
        );
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        entity.setLastProbeStatus(ok ? "SUCCESS" : "FAILED");
        entity.setLastProbeMessage(String.valueOf(result.get("message")));
        entity.setLastProbeAt(LocalDateTime.now());
        repository.save(entity);
        return result;
    }

    public Map<String, Object> probeDraft(OcrConfigRequest req) {
        if (req == null || req.getEndpoint() == null || req.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("请先填写 OCR 接口地址");
        }
        String provider = normalizeProvider(req.getProvider());
        boolean online = ResolvedOcrSettings.isOnlineProvider(provider);
        String apiKey = req.getApiKey() != null ? req.getApiKey().trim() : "";
        if (apiKey.isBlank() && req.getId() != null && !req.getId().isBlank()) {
            apiKey = repository.findById(req.getId())
                    .map(cfg -> decryptKey(cfg.getApiKeyEncrypted()))
                    .orElse("");
        }
        return ping(req.getEndpoint(), apiKey, online, req.getTimeoutSeconds());
    }

    private Map<String, Object> ping(String endpoint, String apiKey, boolean online, Integer timeoutSeconds) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("接口地址为空，无法探测");
        }
        String url = normalizeUrl(endpoint);
        long start = System.currentTimeMillis();
        try {
            URI.create(url);
        } catch (Exception e) {
            return Map.of("success", false, "message", "接口地址不是合法 URL");
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            int timeout = timeoutSeconds != null && timeoutSeconds > 0 ? Math.min(timeoutSeconds, 15) : 10;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout));
            if (online && apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<Void> response;
            try {
                response = client.send(builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception headEx) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeout))
                        .GET();
                if (online && apiKey != null && !apiKey.isBlank()) {
                    getBuilder.header("Authorization", "Bearer " + apiKey);
                }
                response = client.send(getBuilder.build(), HttpResponse.BodyHandlers.discarding());
            }
            long cost = System.currentTimeMillis() - start;
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                String msg = "鉴权失败 (HTTP " + status + ")，请检查 API Key (耗时 " + cost + "ms)";
                return Map.of("success", false, "message", msg, "status", status, "costMs", cost);
            }
            boolean reachable = status < 500;
            String msg;
            if (!reachable) {
                msg = "服务返回 HTTP " + status + " (耗时 " + cost + "ms)";
            } else if (online && (apiKey == null || apiKey.isBlank())) {
                msg = "地址可达 (HTTP " + status + ")，未填写 Key，鉴权未验证 (耗时 " + cost + "ms)";
            } else {
                msg = "连通正常 (HTTP " + status + "，耗时 " + cost + "ms)";
            }
            return Map.of("success", reachable, "message", msg, "status", status, "costMs", cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return Map.of("success", false, "message", "连通失败: " + err + " (耗时 " + cost + "ms)", "costMs", cost);
        }
    }

    public static String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return "LOCAL_PADDLE_OCR";
        }
        String p = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        if (p.contains("ONLINE") || p.contains("CLOUD") || p.contains("QWEN") || p.contains("OPENAI")) {
            return "ONLINE_API";
        }
        return "LOCAL_PADDLE_OCR";
    }

    public String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return secretCrypto.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("解密 OCR Key 异常: {}", e.getMessage());
            return "";
        }
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
