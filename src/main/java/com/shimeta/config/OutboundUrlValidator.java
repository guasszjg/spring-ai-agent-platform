package com.shimeta.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public class OutboundUrlValidator {

    private final boolean allowPrivateAddresses;

    public OutboundUrlValidator(
            @Value("${app.security.allow-private-provider-urls:false}") boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public void validateProviderBaseUrl(String rawUrl) {
        validate(rawUrl, "模型 Base URL", false);
    }

    /**
     * @param label                说明里用的名称，如「MCP 服务地址」
     * @param allowPrivateOverride 管理员对这一个地址单独放行内网访问；全局开关打开时本来就允许
     */
    public void validate(String rawUrl, String label, boolean allowPrivateOverride) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " 格式无效");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(label + " 只允许使用 HTTP 或 HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(label + " 必须包含合法主机名，且不能包含用户凭据");
        }
        if (allowPrivateAddresses || allowPrivateOverride) {
            return;
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivateOrReserved(address)) {
                    throw new IllegalArgumentException("出于安全考虑，" + label + " 不能指向本机或内网地址");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 " + label + " 的主机名");
        }
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 127 || first >= 224
                    || first == 100 && second >= 64 && second <= 127;
        }
        int first = bytes[0] & 0xff;
        return (first & 0xfe) == 0xfc;
    }
}
