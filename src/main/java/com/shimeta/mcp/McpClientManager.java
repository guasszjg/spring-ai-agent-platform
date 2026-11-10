package com.shimeta.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.config.SecretCrypto;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 和 MCP 服务之间的连接：按服务缓存已初始化的客户端，配置变了或调用失败就丢弃重建。
 * <p>
 * 只连远程地址（Streamable HTTP / SSE），不执行本地命令。每次建连前都重新做出站地址校验，不跟随重定向。
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 拉取工具列表最多翻这么多页，防止服务返回无穷的分页游标 */
    private static final int MAX_LIST_PAGES = 20;
    static final String TRUNCATED_NOTE = "\n…[结果过长，已截断]";

    private final OutboundUrlValidator urlValidator;
    private final SecretCrypto secretCrypto;
    private final int maxResultChars;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Cached> clients = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** @param version 建连时服务配置的更新时间，配置改过就不再复用 */
    private record Cached(McpSyncClient client, String version) {
    }

    public McpClientManager(OutboundUrlValidator urlValidator, SecretCrypto secretCrypto,
                            @Value("${app.mcp.max-result-chars:8000}") int maxResultChars) {
        this.urlValidator = urlValidator;
        this.secretCrypto = secretCrypto;
        this.maxResultChars = Math.max(500, maxResultChars);
    }

    /** 连接并拉取全部工具；用一次性的连接，不进缓存（测试连接、刷新工具列表用） */
    public List<McpSchema.Tool> listTools(McpServer server) {
        McpSyncClient client = open(server);
        try {
            List<McpSchema.Tool> all = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_LIST_PAGES; page++) {
                McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
                if (result.tools() != null) {
                    all.addAll(result.tools());
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
            }
            return all;
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 调用工具，返回交给模型的文本。复用缓存的连接；连接是旧的而且失败得很快（多半是会话过期）时重连一次，
     * 超时和写操作工具不重试，避免同一个操作执行两遍。
     */
    public String callTool(McpServer server, String toolName, Map<String, Object> arguments, boolean writeOperation) {
        String version = String.valueOf(server.getUpdatedAt());
        Acquired first = acquire(server, version);
        try {
            return doCall(first.cached().client(), toolName, arguments);
        } catch (RuntimeException e) {
            discard(server.getId(), first.cached());
            if (!first.reused() || writeOperation || isTimeout(e)) {
                throw e;
            }
            log.info("MCP server [{}] call failed on a cached connection, reconnecting once: {}", server.getName(), e.getMessage());
            Acquired second = acquire(server, version);
            try {
                return doCall(second.cached().client(), toolName, arguments);
            } catch (RuntimeException retryError) {
                discard(server.getId(), second.cached());
                throw retryError;
            }
        }
    }

    private record Acquired(Cached cached, boolean reused) {
    }

    /** 取缓存的连接，没有或配置变了就新建；同一个服务同时只建一条 */
    private Acquired acquire(McpServer server, String version) {
        synchronized (locks.computeIfAbsent(server.getId(), id -> new Object())) {
            Cached cached = clients.get(server.getId());
            if (cached != null && cached.version().equals(version)) {
                return new Acquired(cached, true);
            }
            if (cached != null) {
                discard(server.getId(), cached);
            }
            Cached created = new Cached(open(server), version);
            clients.put(server.getId(), created);
            return new Acquired(created, false);
        }
    }

    /** 只丢弃出问题的这一条连接；别的线程可能已经换上了新连接 */
    private void discard(String serverId, Cached cached) {
        if (clients.remove(serverId, cached)) {
            closeQuietly(cached.client());
        }
    }

    public void evict(String serverId) {
        Cached removed = clients.remove(serverId);
        if (removed != null) {
            closeQuietly(removed.client());
        }
    }

    @PreDestroy
    public void closeAll() {
        new ArrayList<>(clients.keySet()).forEach(this::evict);
    }

    private String doCall(McpSyncClient client, String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, arguments != null ? arguments : Map.of()));
        return toText(result);
    }

    McpSyncClient open(McpServer server) {
        String url = realUrl(server);
        urlValidator.validate(url, "MCP 服务地址", Boolean.TRUE.equals(server.getAllowPrivateAddress()));
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        Map<String, String> headers = authHeaders(server);
        McpSyncHttpClientRequestCustomizer customizer = (builder, method, target, body, context) ->
                headers.forEach(builder::setHeader);
        // 不跟随重定向：地址校验只针对填写的主机，跳转到别处（比如内网）就绕过了校验
        HttpClient.Builder http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT);
        McpClientTransport transport = McpTransportType.SSE.equals(server.getTransport())
                ? HttpClientSseClientTransport.builder(base).sseEndpoint(endpoint).clientBuilder(http)
                .connectTimeout(CONNECT_TIMEOUT).httpRequestCustomizer(customizer).build()
                : HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint).clientBuilder(http)
                .connectTimeout(CONNECT_TIMEOUT).httpRequestCustomizer(customizer).build();
        int timeout = server.getTimeoutSeconds() != null ? Math.max(3, Math.min(300, server.getTimeoutSeconds())) : 30;
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("agentmatrix", "1.0"))
                .requestTimeout(Duration.ofSeconds(timeout))
                .initializationTimeout(Duration.ofSeconds(Math.min(timeout, 30)))
                .build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw e;
        }
    }

    /** 完整地址加密保存在 urlEncrypted；url 字段是打过码给人看的 */
    private String realUrl(McpServer server) {
        String encrypted = server.getUrlEncrypted();
        String url = encrypted != null && !encrypted.isBlank() ? secretCrypto.decrypt(encrypted) : server.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MCP 服务地址不能为空");
        }
        return url.trim();
    }

    private Map<String, String> authHeaders(McpServer server) {
        Map<String, String> headers = new LinkedHashMap<>();
        String encrypted = server.getAuthEncrypted();
        if (encrypted == null || encrypted.isBlank() || McpAuthType.NONE.equals(server.getAuthType())) {
            return headers;
        }
        String secret = secretCrypto.decrypt(encrypted);
        if (secret == null || secret.isBlank()) {
            return headers;
        }
        if (McpAuthType.BEARER.equals(server.getAuthType())) {
            headers.put("Authorization", "Bearer " + secret.trim());
        } else if (McpAuthType.HEADERS.equals(server.getAuthType())) {
            headers.putAll(parseHeaders(secret));
        }
        return headers;
    }

    /** 自定义请求头 JSON：只接受字符串值；Host、Content-Length 等由 HTTP 客户端管理的头不能设置 */
    Map<String, String> parseHeaders(String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                throw new IllegalArgumentException("自定义请求头必须是 JSON 对象");
            }
            root.fields().forEachRemaining(e -> {
                String name = e.getKey().trim();
                String lower = name.toLowerCase();
                if (name.isEmpty() || !e.getValue().isValueNode()
                        || List.of("host", "content-length", "connection", "upgrade", "expect").contains(lower)) {
                    return;
                }
                headers.put(name, e.getValue().asText());
            });
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("自定义请求头必须是合法的 JSON 对象");
        }
        return headers;
    }

    /** 把工具返回的内容拼成文本；图片等非文本内容只留占位，太长时截断并告诉模型 */
    String toText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        if (result != null && result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                String piece;
                if (content instanceof McpSchema.TextContent text) {
                    piece = text.text();
                } else if (content instanceof McpSchema.EmbeddedResource resource
                        && resource.resource() instanceof McpSchema.TextResourceContents textResource) {
                    piece = textResource.text();
                } else {
                    piece = "[非文本内容已省略]";
                }
                if (piece != null && !piece.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(piece);
                }
            }
        }
        if (sb.length() == 0 && result != null && result.structuredContent() != null) {
            try {
                sb.append(objectMapper.writeValueAsString(result.structuredContent()));
            } catch (Exception ignored) {
                sb.append(result.structuredContent());
            }
        }
        String text = sb.length() == 0 ? "（工具没有返回内容）" : sb.toString();
        if (text.length() > maxResultChars) {
            text = text.substring(0, maxResultChars) + TRUNCATED_NOTE;
        }
        return result != null && Boolean.TRUE.equals(result.isError()) ? McpToolInvoker.FAILURE_PREFIX + text : text;
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 给人看的失败原因：超时、连不上、鉴权失败等常见情况翻成明确的说法，其余取最里层有内容的报错 */
    public static String describeError(Throwable e) {
        if (isTimeout(e)) {
            return "请求超时";
        }
        String message = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof java.net.UnknownHostException) {
                return "无法连接到服务地址";
            }
            if (t instanceof io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException) {
                return "鉴权失败（服务返回 401 / 403），请检查 Token 或请求头";
            }
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                message = t.getMessage();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        if (message == null) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Exception e) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
