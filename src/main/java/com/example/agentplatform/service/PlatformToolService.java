package com.example.agentplatform.service;

import com.example.agentplatform.config.OutboundUrlValidator;
import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.model.PlatformTool;
import com.example.agentplatform.model.PlatformToolUpdateRequest;
import com.example.agentplatform.model.PlatformToolView;
import com.example.agentplatform.repository.PlatformToolRepository;
import com.example.agentplatform.tool.AgentToolRegistry;
import com.example.agentplatform.tool.HttpToolSpec;
import com.example.agentplatform.tool.PlatformToolCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PlatformToolService {

    public static final String BOCHA_TOOL_ID = "tool-bocha-web-search";
    public static final String BOCHA_TOOL_CODE = "bocha_web_search";

    private final PlatformToolRepository repository;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;
    private final AgentToolRegistry toolRegistry;
    private final OutboundUrlValidator urlValidator;

    @PersistenceContext
    private EntityManager entityManager;

    public PlatformToolService(PlatformToolRepository repository,
                               SecretCrypto secretCrypto,
                               ObjectMapper objectMapper,
                               AgentToolRegistry toolRegistry,
                               OutboundUrlValidator urlValidator) {
        this.repository = repository;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.toolRegistry = toolRegistry;
        this.urlValidator = urlValidator;
    }

    @Transactional
    public List<PlatformToolView> list() {
        seedMissingBuiltins();
        List<PlatformToolView> views = repository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toView)
                .collect(Collectors.toList());
        if (!views.isEmpty()) {
            return views;
        }
        return PlatformToolCatalog.builtins().stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Transactional
    public PageResult<PlatformToolView> page(String keyword, String category, int page, int size) {
        List<PlatformToolView> all = list();
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String cat = category == null || category.isBlank() || "ALL".equalsIgnoreCase(category) ? "" : category.trim();
        List<PlatformToolView> filtered = all.stream()
                .filter(item -> cat.isEmpty() || cat.equalsIgnoreCase(item.getCategory()))
                .filter(item -> q.isEmpty() || matches(item, q))
                .collect(Collectors.toList());
        int safeSize = size <= 0 ? 6 : Math.min(size, 50);
        int safePage = Math.max(page, 1);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PageResult<>(new ArrayList<>(filtered.subList(from, to)), filtered.size(), safePage, safeSize);
    }

    @Transactional
    public PlatformToolView create(PlatformToolUpdateRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (req.getDescription() == null || req.getDescription().isBlank()) {
            throw new IllegalArgumentException("请填写工具提示词，告诉模型何时调用");
        }
        Map<String, Object> config = req.getConfig() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(req.getConfig());
        String url = stringVal(config.get("url"));
        if (url.isBlank()) {
            throw new IllegalArgumentException("自定义工具必须填写 HTTP 接口地址");
        }
        urlValidator.validateProviderBaseUrl(url);
        String method = stringVal(config.get("method"));
        if (method.isBlank()) {
            method = "POST";
        }
        config.put("url", url.trim());
        config.put("method", method.toUpperCase(Locale.ROOT));
        if (!config.containsKey("timeoutSeconds")) {
            config.put("timeoutSeconds", 8);
        }

        String name = req.getName().trim();
        String title = req.getTitle() == null || req.getTitle().isBlank() ? name : req.getTitle().trim();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        PlatformTool entity = new PlatformTool();
        entity.setId("tool-http-" + suffix);
        entity.setCode("http_" + suffix);
        entity.setName(name);
        entity.setTitle(title);
        entity.setPrefix("http");
        entity.setCategory("CUSTOM");
        entity.setIcon("fa-solid fa-plug");
        entity.setIconClass("icon-orange");
        entity.setHelp(req.getHelp() == null || req.getHelp().isBlank() ? "用户自定义 HTTP 工具" : req.getHelp().trim());
        entity.setDescription(req.getDescription().trim());
        entity.setConfigJson(writeConfig(config));
        entity.setEnabled(req.getEnabled() == null || req.getEnabled());
        entity.setBuiltin(false);
        entity.setSortOrder(200);
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        return toView(repository.save(entity));
    }

    @Transactional
    public void delete(String id) {
        PlatformTool entity = require(id);
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw new IllegalArgumentException("内置工具不能删除，只能停用或修改默认参数");
        }
        repository.deleteById(id);
    }

    public List<HttpToolSpec> httpRuntimeSpecs() {
        List<HttpToolSpec> specs = new ArrayList<>();
        for (PlatformTool entity : repository.findAll()) {
            if (!isHttp(entity) || !Boolean.TRUE.equals(entity.getEnabled())) {
                continue;
            }
            Map<String, Object> config = readConfig(entity.getConfigJson());
            HttpToolSpec spec = new HttpToolSpec();
            spec.setCode(entity.getCode());
            spec.setName(entity.getName());
            spec.setDescription(entity.getDescription());
            spec.setMethod(stringVal(config.get("method")));
            spec.setUrl(stringVal(config.get("url")));
            spec.setApiKey(decrypt(entity));
            spec.setTimeoutSeconds(intVal(config.get("timeoutSeconds"), 8));
            specs.add(spec);
        }
        return specs;
    }

    public Map<String, Map<String, Object>> runtimeConfigs() {
        LinkedHashMap<String, Map<String, Object>> configs = new LinkedHashMap<>();
        for (PlatformTool entity : repository.findAll()) {
            if (entity.getCode() == null || entity.getCode().isBlank()) {
                continue;
            }
            configs.put(entity.getCode(), readConfig(entity.getConfigJson()));
        }
        return configs;
    }

    private void seedMissingBuiltins() {
        for (PlatformTool builtin : PlatformToolCatalog.builtins()) {
            if (!repository.existsById(builtin.getId()) && repository.findByCode(builtin.getCode()).isEmpty()) {
                repository.save(builtin);
            }
        }
        if (entityManager != null) {
            entityManager.flush();
        }
    }

    @Transactional(readOnly = true)
    public PlatformToolView get(String id) {
        return toView(require(id));
    }

    @Transactional
    public PlatformToolView update(String id, PlatformToolUpdateRequest req) {
        PlatformTool entity = require(id);
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            entity.setTitle(req.getTitle().trim());
        }
        if (req.getHelp() != null) {
            entity.setHelp(req.getHelp().trim());
        }
        if (req.getDescription() != null && !req.getDescription().isBlank()) {
            entity.setDescription(req.getDescription().trim());
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        if (req.getName() != null && !req.getName().isBlank() && !Boolean.TRUE.equals(entity.getBuiltin())) {
            entity.setName(req.getName().trim());
        }
        if (req.getConfig() != null) {
            Map<String, Object> config = new LinkedHashMap<>(req.getConfig());
            if (isHttp(entity)) {
                String url = stringVal(config.get("url"));
                if (url.isBlank()) {
                    throw new IllegalArgumentException("自定义工具必须填写 HTTP 接口地址");
                }
                urlValidator.validateProviderBaseUrl(url);
                config.put("url", url.trim());
                String method = stringVal(config.get("method"));
                config.put("method", method.isBlank() ? "POST" : method.toUpperCase(Locale.ROOT));
            }
            entity.setConfigJson(writeConfig(config));
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            if (!isBocha(entity) && !isHttp(entity)) {
                throw new IllegalArgumentException("当前工具不支持配置 API Key");
            }
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        return toView(repository.save(entity));
    }

    @Transactional
    public PlatformToolView setEnabled(String id, boolean enabled) {
        PlatformTool entity = require(id);
        entity.setEnabled(enabled);
        return toView(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public String getBochaApiKey() {
        return repository.findById(BOCHA_TOOL_ID)
                .or(() -> repository.findByCode(BOCHA_TOOL_CODE))
                .map(PlatformTool::getApiKeyEncrypted)
                .filter(value -> value != null && !value.isBlank())
                .map(secretCrypto::decrypt)
                .orElse(null);
    }

    public Map<String, Object> testConnection(String id, String draftApiKey) {
        PlatformTool entity = require(id);
        String key = draftApiKey != null && !draftApiKey.isBlank() ? draftApiKey.trim() : decrypt(entity);
        if (isBocha(entity)) {
            return toolRegistry.testBochaConnection(key);
        }
        if (isHttp(entity)) {
            Map<String, Object> config = readConfig(entity.getConfigJson());
            HttpToolSpec spec = new HttpToolSpec();
            spec.setCode(entity.getCode());
            spec.setName(entity.getName());
            spec.setMethod(stringVal(config.get("method")));
            spec.setUrl(stringVal(config.get("url")));
            spec.setApiKey(key);
            spec.setTimeoutSeconds(intVal(config.get("timeoutSeconds"), 8));
            return toolRegistry.testHttpTool(spec);
        }
        throw new IllegalArgumentException("当前工具无需连通性测试");
    }

    private PlatformTool require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到平台工具: " + id));
    }

    private boolean isHttp(PlatformTool entity) {
        if (entity == null) {
            return false;
        }
        if ("CUSTOM".equalsIgnoreCase(entity.getCategory()) || "http".equalsIgnoreCase(entity.getPrefix())) {
            return true;
        }
        return !Boolean.TRUE.equals(entity.getBuiltin()) && stringVal(readConfig(entity.getConfigJson()).get("url")).length() > 0;
    }

    private boolean matches(PlatformToolView item, String q) {
        return contains(item.getName(), q) || contains(item.getTitle(), q) || contains(item.getPrefix(), q)
                || contains(item.getCode(), q) || contains(item.getHelp(), q) || contains(item.getDescription(), q);
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private String stringVal(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intVal(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean isBocha(PlatformTool entity) {
        return BOCHA_TOOL_ID.equals(entity.getId())
                || BOCHA_TOOL_CODE.equals(entity.getCode())
                || "bocha".equalsIgnoreCase(entity.getPrefix())
                || "bocha".equalsIgnoreCase(entity.getCustomIcon());
    }

    private PlatformToolView toView(PlatformTool entity) {
        PlatformToolView view = new PlatformToolView();
        view.setId(entity.getId());
        view.setCode(entity.getCode());
        view.setName(entity.getName());
        view.setTitle(entity.getTitle());
        view.setPrefix(entity.getPrefix());
        view.setCategory(entity.getCategory());
        view.setIcon(entity.getIcon());
        view.setIconClass(entity.getIconClass());
        view.setCustomIcon(entity.getCustomIcon());
        view.setHelp(entity.getHelp());
        view.setDescription(entity.getDescription());
        view.setConfig(readConfig(entity.getConfigJson()));
        view.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        view.setBuiltin(Boolean.TRUE.equals(entity.getBuiltin()));
        view.setSortOrder(entity.getSortOrder());
        view.setUpdatedAt(entity.getUpdatedAt());
        String plain = decrypt(entity);
        boolean hasKey = plain != null && !plain.isBlank();
        view.setHasApiKey(hasKey);
        if (hasKey) {
            if (plain.length() <= 8) {
                view.setApiKeyMasked("****");
            } else {
                view.setApiKeyMasked(plain.substring(0, 3) + "..." + plain.substring(plain.length() - 4));
            }
        } else {
            view.setApiKeyMasked("");
        }
        return view;
    }

    private String decrypt(PlatformTool entity) {
        String encrypted = entity.getApiKeyEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return secretCrypto.decrypt(encrypted);
    }

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数无法序列化");
        }
    }
}
