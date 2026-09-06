package com.example.agentplatform.config;

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
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Base URL 格式无效");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Base URL 只允许使用 HTTP 或 HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Base URL 必须包含合法主机名，且不能包含用户凭据");
        }
        if (allowPrivateAddresses) {
            return;
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivateOrReserved(address)) {
                    throw new IllegalArgumentException("出于安全考虑，模型 Base URL 不能指向本机或内网地址");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析模型 Base URL 的主机名");
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
