package com.shimeta.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.config.SecretCrypto;
import com.shimeta.model.PlatformTool;
import com.shimeta.repository.PlatformToolRepository;
import com.shimeta.security.CurrentActor;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP 服务管理：添加服务、拉取并同步工具、审核启用，以及对话时把工具调用转发给服务。
 * <p>
 * 工具拉下来默认不启用，管理员逐个审核后才能被智能体选用；上游改了工具的名称、说明或参数格式，
 * 自动停用并标记为「有变化」，防止被改过的工具说明诱导模型。
 */
@Service
public class McpServerService implements McpToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNTESTED = "UNTESTED";
    public static final String CATEGORY_SEARCH = "SEARCH";
    public static final String CATEGORY_MAP = "MAP";
    public static final String CATEGORY_DATABASE = "DATABASE";
    public static final String CATEGORY_FILE = "FILE";
    public static final String CATEGORY_OTHER = "OTHER";
    private static final List<String> CATEGORIES = List.of(CATEGORY_SEARCH, CATEGORY_MAP, CATEGORY_DATABASE, CATEGORY_FILE, CATEGORY_OTHER);

    /** 函数名上限：OpenAI 兼容接口要求不超过 64 个字符，只能是字母、数字、下划线、横线 */
    static final int MAX_FUNCTION_NAME = 64;
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9]{1,15}");
    private static final int MAX_DESCRIPTION = 4000;
    private static final Pattern WRITE_HINT = Pattern.compile(
            "(^|[_\\-.])(create|update|delete|remove|send|write|insert|post|put|set|add|edit|modify|move|upload|execute|exec|run|drop|cancel|pay|order|publish)([_\\-.]|$)");

    private final McpServerRepository serverRepository;
    private final McpServerToolRepository toolRepository;
    private final PlatformToolRepository platformToolRepository;
    private final McpClientManager clientManager;
    private final SecretCrypto secretCrypto;
    private final OutboundUrlValidator urlValidator;
    private final ObjectMapper objectMapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public McpServerService(McpServerRepository serverRepository, McpServerToolRepository toolRepository,
                            PlatformToolRepository platformToolRepository, McpClientManager clientManager,
                            SecretCrypto secretCrypto, OutboundUrlValidator urlValidator) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.platformToolRepository = platformToolRepository;
        this.clientManager = clientManager;
        this.secretCrypto = secretCrypto;
        this.urlValidator = urlValidator;
    }

    private com.shimeta.service.ToolUsageService usageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsageService(com.shimeta.service.ToolUsageService usageService) {
        this.usageService = usageService;
    }

    // ==================== 服务 ====================

    public List<McpServerView> list() {
        Map<String, List<McpServerTool>> toolsByServer = toolRepository.findAll().stream()
                .collect(Collectors.groupingBy(McpServerTool::getServerId));
        return serverRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(server -> toView(server, toolsByServer.getOrDefault(server.getId(), List.of())))
                .toList();
    }

    public McpServerView get(String id) {
        McpServer server = require(id);
        return toView(server, toolRepository.findByServerIdOrderByToolNameAsc(id));
    }

    public McpServerView create(McpServerRequest req) {
        McpServer server = new McpServer();
        server.setId("mcp-" + UUID.randomUUID().toString().substring(0, 8));
        apply(server, req, true);
        String slug = req.getSlug() != null && !req.getSlug().isBlank()
                ? req.getSlug().trim().toLowerCase(Locale.ROOT) : deriveSlug(server.getName());
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("标识只能用小写字母和数字，字母开头，2–16 个字符");
        }
        if (serverRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("标识「" + slug + "」已被其它 MCP 服务使用");
        }
        server.setSlug(slug);
        server.setLastStatus(STATUS_UNTESTED);
        CurrentActor actor = CurrentActor.get();
        server.setCreatedBy(actor != null ? actor.getUserId() : null);
        return toView(serverRepository.save(server), List.of());
    }

    public McpServerView update(String id, McpServerRequest req) {
        McpServer server = require(id);
        String urlBefore = server.getUrlEncrypted();
        apply(server, req, false);
        // 地址换了就是另一个服务：已审核的工具全部退回待审核
        if (!Objects.equals(urlBefore, server.getUrlEncrypted())) {
            for (McpServerTool tool : toolRepository.findByServerIdOrderByToolNameAsc(id)) {
                tool.setEnabled(false);
                tool.setReviewStatus(McpReviewStatus.CHANGED);
                toolRepository.save(tool);
            }
            server.setLastStatus(STATUS_UNTESTED);
            server.setLastMessage("地址已修改，请重新测试并审核工具");
        }
        McpServer saved = serverRepository.save(server);
        clientManager.evict(id);
        return toView(saved, toolRepository.findByServerIdOrderByToolNameAsc(id));
    }

    public McpServerView setEnabled(String id, boolean enabled) {
        McpServer server = require(id);
        server.setEnabled(enabled);
        McpServer saved = serverRepository.save(server);
        if (!enabled) {
            clientManager.evict(id);
        }
        return toView(saved, toolRepository.findByServerIdOrderByToolNameAsc(id));
    }

    @Transactional
    public void delete(String id) {
        require(id);
        toolRepository.deleteByServerId(id);
        serverRepository.deleteById(id);
        clientManager.evict(id);
    }

    /** 保存前先试一下填写的地址和鉴权；编辑时没重新填的地址、密钥沿用已保存的 */
    public Map<String, Object> testDraft(String existingId, McpServerRequest req) {
        McpServer draft = new McpServer();
        if (existingId != null && !existingId.isBlank()) {
            McpServer saved = require(existingId);
            draft.setName(saved.getName());
            draft.setUrl(saved.getUrl());
            draft.setUrlEncrypted(saved.getUrlEncrypted());
            draft.setTransport(saved.getTransport());
            draft.setAuthType(saved.getAuthType());
            draft.setAuthEncrypted(saved.getAuthEncrypted());
            draft.setTimeoutSeconds(saved.getTimeoutSeconds());
            draft.setAllowPrivateAddress(saved.getAllowPrivateAddress());
        }
        apply(draft, req, existingId == null || existingId.isBlank());
        return probe(draft);
    }

    public Map<String, Object> test(String id) {
        McpServer server = require(id);
        Map<String, Object> result = probe(server);
        recordStatus(server, Boolean.TRUE.equals(result.get("success")), String.valueOf(result.get("message")));
        return result;
    }

    private Map<String, Object> probe(McpServer server) {
        Map<String, Object> result = new LinkedHashMap<>();
        long started = System.currentTimeMillis();
        try {
            List<McpSchema.Tool> tools = clientManager.listTools(server);
            result.put("success", true);
            result.put("toolCount", tools.size());
            result.put("message", "连接成功，服务提供 " + tools.size() + " 个工具");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败：" + McpClientManager.describeError(e));
        }
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    // ==================== 工具同步与审核 ====================

    /**
     * 从服务拉取最新的工具列表并和已保存的对比：新工具入库待审核；定义变了的停用并标记有变化；
     * 服务里已经没有的停用并标记已删除。
     */
    public McpServerView refreshTools(String id) {
        McpServer server = require(id);
        List<McpSchema.Tool> remote;
        try {
            remote = clientManager.listTools(server);
        } catch (Exception e) {
            String message = "拉取工具失败：" + McpClientManager.describeError(e);
            recordStatus(server, false, message);
            throw new IllegalStateException(message);
        }
        Map<String, McpServerTool> existing = new HashMap<>();
        for (McpServerTool tool : toolRepository.findByServerIdOrderByToolNameAsc(id)) {
            existing.put(tool.getToolName(), tool);
        }
        int added = 0, changed = 0, removed = 0;
        for (McpSchema.Tool tool : remote) {
            if (tool.name() == null || tool.name().isBlank() || tool.name().length() > 200) {
                continue;
            }
            String description = truncate(tool.description(), MAX_DESCRIPTION);
            String schema = toJson(tool.inputSchema() != null ? tool.inputSchema() : Map.of("type", "object"));
            String fingerprint = fingerprint(tool.name(), description, schema);
            McpServerTool saved = existing.remove(tool.name());
            if (saved == null) {
                saved = new McpServerTool();
                saved.setId("mcpt-" + UUID.randomUUID().toString().substring(0, 12));
                saved.setServerId(id);
                saved.setToolName(tool.name());
                saved.setFunctionName(uniqueFunctionName(server.getSlug(), tool.name()));
                saved.setCategory(guessCategory(tool.name(), description));
                saved.setWriteOperation(guessWriteOperation(tool));
                saved.setEnabled(false);
                saved.setReviewStatus(McpReviewStatus.PENDING);
                added++;
            } else if (!fingerprint.equals(saved.getFingerprint())) {
                saved.setEnabled(false);
                saved.setReviewStatus(McpReviewStatus.CHANGED);
                saved.setWriteOperation(Boolean.TRUE.equals(saved.getWriteOperation()) || guessWriteOperation(tool));
                changed++;
            } else if (McpReviewStatus.REMOVED.equals(saved.getReviewStatus())) {
                // 之前被上游删掉又加回来了：重新审核
                saved.setReviewStatus(McpReviewStatus.PENDING);
            }
            saved.setTitle(truncate(toolTitle(tool), 200));
            saved.setDescription(description);
            saved.setInputSchema(schema);
            saved.setFingerprint(fingerprint);
            toolRepository.save(saved);
        }
        for (McpServerTool gone : existing.values()) {
            if (!McpReviewStatus.REMOVED.equals(gone.getReviewStatus())) {
                gone.setEnabled(false);
                gone.setReviewStatus(McpReviewStatus.REMOVED);
                toolRepository.save(gone);
                removed++;
            }
        }
        String message = "已同步 " + remote.size() + " 个工具"
                + (added + changed + removed > 0 ? "（新增 " + added + "，有变化 " + changed + "，已删除 " + removed + "）" : "，没有变化");
        recordStatus(server, true, message);
        return get(id);
    }

    public McpToolView updateTool(String serverId, String toolId, McpToolUpdateRequest req) {
        McpServer server = require(serverId);
        McpServerTool tool = toolRepository.findById(toolId)
                .filter(t -> serverId.equals(t.getServerId()))
                .orElseThrow(() -> new IllegalArgumentException("工具不存在"));
        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            String category = req.getCategory().trim().toUpperCase(Locale.ROOT);
            if (!CATEGORIES.contains(category)) {
                throw new IllegalArgumentException("不支持的工具类别: " + req.getCategory());
            }
            tool.setCategory(category);
        }
        if (req.getWriteOperation() != null) {
            tool.setWriteOperation(req.getWriteOperation());
        }
        if (req.getEnabled() != null) {
            if (req.getEnabled()) {
                if (McpReviewStatus.REMOVED.equals(tool.getReviewStatus())) {
                    throw new IllegalArgumentException("服务里已经没有这个工具，不能启用");
                }
                if (Boolean.TRUE.equals(tool.getWriteOperation()) && !Boolean.TRUE.equals(req.getConfirmWrite())) {
                    throw new IllegalArgumentException("这是写操作类工具（会创建、修改或删除数据），需要单独确认后才能启用");
                }
                CurrentActor actor = CurrentActor.get();
                tool.setReviewStatus(McpReviewStatus.APPROVED);
                tool.setReviewedBy(actor != null ? actor.getUserId() : null);
                tool.setReviewedAt(LocalDateTime.now());
            }
            tool.setEnabled(req.getEnabled());
        }
        return toToolView(toolRepository.save(tool), server, similarToolNames());
    }

    // ==================== 对话时使用 ====================

    /** 智能体可以选用的工具：服务启用、工具已审核并启用 */
    public List<McpToolSpec> runtimeSpecs() {
        List<McpServerTool> tools = toolRepository.findByEnabledTrue();
        if (tools.isEmpty()) {
            return List.of();
        }
        Map<String, McpServer> servers = new HashMap<>();
        for (McpServer server : serverRepository.findAll()) {
            if (Boolean.TRUE.equals(server.getEnabled())) {
                servers.put(server.getId(), server);
            }
        }
        List<McpToolSpec> specs = new ArrayList<>();
        for (McpServerTool tool : tools) {
            McpServer server = servers.get(tool.getServerId());
            if (server == null || !McpReviewStatus.APPROVED.equals(tool.getReviewStatus())) {
                continue;
            }
            specs.add(new McpToolSpec(tool.getFunctionName(), server.getId(), server.getName(), tool.getToolName(),
                    tool.getTitle(), tool.getDescription(), parseSchema(tool.getInputSchema()),
                    Boolean.TRUE.equals(tool.getWriteOperation())));
        }
        return specs;
    }

    /** 智能体工具选择里展示的条目 */
    public List<McpToolView> availableTools() {
        Map<String, McpServer> servers = serverRepository.findAll().stream()
                .filter(server -> Boolean.TRUE.equals(server.getEnabled()))
                .collect(Collectors.toMap(McpServer::getId, s -> s));
        Map<String, List<String>> similar = similarToolNames();
        return toolRepository.findByEnabledTrue().stream()
                .filter(tool -> servers.containsKey(tool.getServerId()) && McpReviewStatus.APPROVED.equals(tool.getReviewStatus()))
                .map(tool -> toToolView(tool, servers.get(tool.getServerId()), similar))
                .toList();
    }

    @Override
    public String call(McpToolSpec spec, String argumentsJson) {
        McpServer server = serverRepository.findById(spec.serverId()).orElse(null);
        if (server == null || !Boolean.TRUE.equals(server.getEnabled())) {
            return FAILURE_PREFIX + "MCP 服务「" + spec.serverName() + "」已停用或被删除";
        }
        Map<String, Object> arguments;
        try {
            arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of() : objectMapper.readValue(argumentsJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return FAILURE_PREFIX + "工具参数不是合法的 JSON 对象";
        }
        try {
            return clientManager.callTool(server, spec.toolName(), arguments, spec.writeOperation());
        } catch (Exception e) {
            String reason = McpClientManager.describeError(e);
            log.warn("MCP tool [{}] on server [{}] failed: {}", spec.toolName(), server.getName(), reason);
            return FAILURE_PREFIX + "服务「" + server.getName() + "」调用失败：" + reason;
        }
    }

    // ==================== 内部 ====================

    private void apply(McpServer server, McpServerRequest req, boolean creating) {
        if (req == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            server.setName(truncate(req.getName().trim(), 120));
        } else if (creating) {
            throw new IllegalArgumentException("请填写服务名称");
        }
        if (req.getTransport() != null && !req.getTransport().isBlank()) {
            String transport = req.getTransport().trim().toUpperCase(Locale.ROOT);
            if (!McpTransportType.isValid(transport)) {
                throw new IllegalArgumentException("传输方式只支持 STREAMABLE_HTTP 或 SSE");
            }
            server.setTransport(transport);
        }
        if (req.getAllowPrivateAddress() != null) {
            server.setAllowPrivateAddress(req.getAllowPrivateAddress());
        }
        // 没填，或原样传回了打过码的展示地址：沿用已保存的地址
        String url = req.getUrl() != null ? req.getUrl().trim() : "";
        if (!url.isEmpty() && !url.equals(server.getUrl()) && !url.equals(savedUrl(server))) {
            urlValidator.validate(url, "MCP 服务地址", Boolean.TRUE.equals(server.getAllowPrivateAddress()));
            server.setUrl(truncate(maskUrl(url), 1000));
            server.setUrlEncrypted(secretCrypto.encrypt(url));
        } else if (creating && url.isEmpty()) {
            throw new IllegalArgumentException("请填写 MCP 服务地址");
        }
        if (req.getAuthType() != null && !req.getAuthType().isBlank()) {
            String authType = req.getAuthType().trim().toUpperCase(Locale.ROOT);
            if (!McpAuthType.isValid(authType)) {
                throw new IllegalArgumentException("鉴权方式只支持 NONE、BEARER 或 HEADERS");
            }
            if (!authType.equals(server.getAuthType())) {
                server.setAuthEncrypted(null);
            }
            server.setAuthType(authType);
        }
        if (McpAuthType.NONE.equals(server.getAuthType())) {
            server.setAuthEncrypted(null);
        } else if (req.getAuthSecret() != null && !req.getAuthSecret().isBlank()) {
            String secret = req.getAuthSecret().trim();
            if (McpAuthType.HEADERS.equals(server.getAuthType()) && clientManager.parseHeaders(secret).isEmpty()) {
                throw new IllegalArgumentException("自定义请求头至少要有一项，例如 {\"X-Api-Key\": \"...\"}");
            }
            server.setAuthEncrypted(secretCrypto.encrypt(secret));
        } else if (server.getAuthEncrypted() == null) {
            throw new IllegalArgumentException(McpAuthType.BEARER.equals(server.getAuthType())
                    ? "请填写 Bearer Token" : "请填写自定义请求头");
        }
        if (req.getTimeoutSeconds() != null) {
            server.setTimeoutSeconds(Math.max(3, Math.min(300, req.getTimeoutSeconds())));
        }
        if (req.getEnabled() != null) {
            server.setEnabled(req.getEnabled());
        }
        if (req.getRemark() != null) {
            server.setRemark(truncate(req.getRemark().trim(), 500));
        }
    }

    private String savedUrl(McpServer server) {
        String encrypted = server.getUrlEncrypted();
        return encrypted == null || encrypted.isBlank() ? null : secretCrypto.decrypt(encrypted);
    }

    private void recordStatus(McpServer server, boolean ok, String message) {
        server.setLastStatus(ok ? STATUS_OK : STATUS_FAILED);
        server.setLastMessage(truncate(message, 500));
        server.setLastCheckedAt(LocalDateTime.now());
        // 只更新状态，不动 updatedAt 之外的配置；连接缓存按 updatedAt 判断，这里顺手丢弃旧连接
        serverRepository.save(server);
        clientManager.evict(server.getId());
    }

    private McpServer require(String id) {
        return serverRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在"));
    }

    /** 查询参数的值打码：有的服务把 Key 放在地址里（如 ?key=xxx），列表里不回显 */
    static String maskUrl(String url) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return url;
        }
        String[] pairs = url.substring(q + 1).split("&");
        List<String> masked = new ArrayList<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            masked.add(eq < 0 ? pair : pair.substring(0, eq + 1) + "****");
        }
        return url.substring(0, q + 1) + String.join("&", masked);
    }

    private String deriveSlug(String name) {
        String ascii = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        ascii = ascii.replaceFirst("^[0-9]+", "");
        String base = ascii.length() >= 2 ? ascii.substring(0, Math.min(12, ascii.length())) : "srv";
        String slug = base;
        for (int i = 2; serverRepository.existsBySlug(slug); i++) {
            slug = base + i;
        }
        return slug;
    }

    /** mcp_&lt;slug&gt;__&lt;工具名&gt;；超长或和已有的撞名时截断并加上工具名的哈希 */
    private String uniqueFunctionName(String slug, String toolName) {
        String name = functionName(slug, toolName, false);
        if (toolRepository.findByFunctionName(name).isPresent()) {
            name = functionName(slug, toolName, true);
        }
        return name;
    }

    static String functionName(String slug, String toolName, boolean forceHash) {
        String prefix = "mcp_" + slug + "__";
        String clean = toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (!forceHash && prefix.length() + clean.length() <= MAX_FUNCTION_NAME) {
            return prefix + clean;
        }
        String hash = sha256(toolName).substring(0, 8);
        int keep = Math.max(0, MAX_FUNCTION_NAME - prefix.length() - hash.length() - 1);
        return prefix + clean.substring(0, Math.min(keep, clean.length())) + "_" + hash;
    }

    static String guessCategory(String name, String description) {
        String text = (name + " " + (description != null ? description : "")).toLowerCase(Locale.ROOT);
        if (containsAny(text, "weather", "geocod", "geo_", "route", "direction", "distance", "poi", "map", "天气", "地图", "导航", "路线", "路径规划", "地理")) {
            return CATEGORY_MAP;
        }
        if (containsAny(text, "web search", "web_search", "websearch", "search the web", "search engine", "联网", "搜索引擎", "网页搜索", "联网搜索")) {
            return CATEGORY_SEARCH;
        }
        if (containsAny(text, "sql", "database", "数据库", "数据表")) {
            return CATEGORY_DATABASE;
        }
        if (containsAny(text, "file", "directory", "文件", "目录")) {
            return CATEGORY_FILE;
        }
        return CATEGORY_OTHER;
    }

    /** 服务声明了只读就按只读；声明了有破坏性，或工具名里有创建、删除、发送这类动词，就按写操作 */
    static boolean guessWriteOperation(McpSchema.Tool tool) {
        McpSchema.ToolAnnotations annotations = tool.annotations();
        if (annotations != null) {
            if (Boolean.TRUE.equals(annotations.readOnlyHint())) {
                return false;
            }
            if (Boolean.TRUE.equals(annotations.destructiveHint())) {
                return true;
            }
        }
        String snake = tool.name().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return WRITE_HINT.matcher(snake).find();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String toolTitle(McpSchema.Tool tool) {
        if (tool.title() != null && !tool.title().isBlank()) {
            return tool.title();
        }
        if (tool.annotations() != null && tool.annotations().title() != null && !tool.annotations().title().isBlank()) {
            return tool.annotations().title();
        }
        return tool.name();
    }

    private String fingerprint(String name, String description, String schema) {
        return sha256(name + "\n" + (description != null ? description : "") + "\n" + schema);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"type\":\"object\"}";
        }
    }

    private Map<String, Object> parseSchema(String json) {
        Map<String, Object> schema = null;
        if (json != null && !json.isBlank()) {
            try {
                schema = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception ignored) {
            }
        }
        if (schema == null) {
            schema = new LinkedHashMap<>();
        }
        // 有的模型接口要求参数必须是带 properties 的 object
        schema.putIfAbsent("type", "object");
        schema.putIfAbsent("properties", new LinkedHashMap<>());
        return schema;
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    /** 每个类别下已经启用的工具名（内置工具和 MCP 工具），用来提示功能重复 */
    private Map<String, List<String>> similarToolNames() {
        Map<String, List<String>> byCategory = new HashMap<>();
        for (PlatformTool tool : platformToolRepository.findAll()) {
            if (Boolean.TRUE.equals(tool.getEnabled()) && tool.getCategory() != null) {
                byCategory.computeIfAbsent(tool.getCategory().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(tool.getName());
            }
        }
        for (McpServerTool tool : toolRepository.findByEnabledTrue()) {
            if (tool.getCategory() != null) {
                byCategory.computeIfAbsent(tool.getCategory(), k -> new ArrayList<>()).add(tool.getFunctionName());
            }
        }
        return byCategory;
    }

    private McpServerView toView(McpServer server, List<McpServerTool> tools) {
        Map<String, List<String>> similar = similarToolNames();
        McpServerView view = new McpServerView();
        view.setId(server.getId());
        view.setName(server.getName());
        view.setSlug(server.getSlug());
        view.setUrl(server.getUrl());
        view.setTransport(server.getTransport());
        view.setAuthType(server.getAuthType());
        view.setHasAuth(server.getAuthEncrypted() != null && !server.getAuthEncrypted().isBlank());
        view.setTimeoutSeconds(server.getTimeoutSeconds());
        view.setAllowPrivateAddress(Boolean.TRUE.equals(server.getAllowPrivateAddress()));
        view.setEnabled(Boolean.TRUE.equals(server.getEnabled()));
        view.setLastStatus(server.getLastStatus());
        view.setLastMessage(server.getLastMessage());
        view.setLastCheckedAt(server.getLastCheckedAt());
        view.setRemark(server.getRemark());
        view.setUpdatedAt(server.getUpdatedAt());
        view.setToolCount(tools.size());
        view.setEnabledToolCount((int) tools.stream().filter(t -> Boolean.TRUE.equals(t.getEnabled())).count());
        view.setPendingToolCount((int) tools.stream().filter(t -> McpReviewStatus.PENDING.equals(t.getReviewStatus())
                || McpReviewStatus.CHANGED.equals(t.getReviewStatus())).count());
        Map<String, com.shimeta.service.ToolUsageService.ToolStats> usage = usageSummary();
        // 已启用的排前面，其次是等待审核的，上游已删除的放最后；同一组按工具名
        view.setTools(tools.stream().sorted(java.util.Comparator
                .comparingInt((McpServerTool tool) -> Boolean.TRUE.equals(tool.getEnabled()) ? 0
                        : McpReviewStatus.REMOVED.equals(tool.getReviewStatus()) ? 2 : 1)
                .thenComparing(McpServerTool::getToolName)).map(tool -> {
            McpToolView toolView = toToolView(tool, server, similar);
            com.shimeta.service.ToolUsageService.ToolStats stats = usage.get(tool.getFunctionName());
            if (stats != null) {
                toolView.setTodayCalls(stats.today());
                toolView.setMonthCalls(stats.month());
                toolView.setTotalCalls(stats.total());
                toolView.setMonthSuccessRate(stats.monthSuccessRate());
                toolView.setMonthAvgLatencyMs(stats.monthAvgLatencyMs());
            }
            return toolView;
        }).toList());
        return view;
    }

    /** MCP 管理页只有超级管理员能看，统计不按智能体过滤 */
    private Map<String, com.shimeta.service.ToolUsageService.ToolStats> usageSummary() {
        if (usageService == null) {
            return Map.of();
        }
        try {
            return usageService.summary(null);
        } catch (Exception e) {
            log.debug("MCP tool usage summary unavailable: {}", e.getMessage());
            return Map.of();
        }
    }

    private McpToolView toToolView(McpServerTool tool, McpServer server, Map<String, List<String>> similar) {
        McpToolView view = new McpToolView();
        view.setId(tool.getId());
        view.setServerId(server.getId());
        view.setServerName(server.getName());
        view.setServerSlug(server.getSlug());
        view.setToolName(tool.getToolName());
        view.setFunctionName(tool.getFunctionName());
        view.setTitle(tool.getTitle());
        view.setDescription(tool.getDescription());
        view.setInputSchema(parseSchema(tool.getInputSchema()));
        view.setCategory(tool.getCategory());
        view.setWriteOperation(Boolean.TRUE.equals(tool.getWriteOperation()));
        view.setEnabled(Boolean.TRUE.equals(tool.getEnabled()));
        view.setReviewStatus(tool.getReviewStatus());
        view.setReviewedAt(tool.getReviewedAt());
        // 「其它」类不提示重复：这一类本来就什么都有
        List<String> same = tool.getCategory() == null || CATEGORY_OTHER.equals(tool.getCategory())
                ? List.of()
                : similar.getOrDefault(tool.getCategory(), List.of()).stream()
                .filter(name -> !name.equals(tool.getFunctionName())).toList();
        view.setSimilarTools(same);
        return view;
    }
}
