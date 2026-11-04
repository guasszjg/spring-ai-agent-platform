package com.shimeta.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * 把平台内的相对地址（如 FAQ 附图 /uploads/knowledge/xxx.png）转成调用方能直接访问的完整网址。
 * 优先使用配置 app.public-base-url（经反向代理、域名或 HTTPS 对外时需要配置），
 * 没配置时用本次请求的协议、主机和端口。
 */
@Component
public class PublicUrlResolver {

    private final String configuredBaseUrl;

    public PublicUrlResolver(@Value("${app.public-base-url:}") String configuredBaseUrl) {
        String base = configuredBaseUrl == null ? "" : configuredBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.configuredBaseUrl = base;
    }

    /** 对外访问的根地址，不以 / 结尾 */
    public String baseUrl(HttpServletRequest request) {
        if (!configuredBaseUrl.isEmpty() || request == null) {
            return configuredBaseUrl;
        }
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }

    /** 相对地址补成完整网址；已经是 http(s) 开头的保持不变 */
    public static List<String> absolute(List<String> paths, String baseUrl) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        String base = baseUrl == null ? "" : baseUrl;
        return paths.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .map(p -> p.startsWith("http://") || p.startsWith("https://")
                        ? p
                        : base + (p.startsWith("/") ? p : "/" + p))
                .toList();
    }
}
