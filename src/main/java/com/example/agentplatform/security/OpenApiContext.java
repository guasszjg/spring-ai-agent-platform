package com.example.agentplatform.security;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.model.UserRole;

import java.util.List;

public class OpenApiContext {

    private static final ThreadLocal<OpenApiContext> CURRENT = new ThreadLocal<>();

    private final String requestId;
    private final OpenApiKey key;
    private final AppUser owner;
    private ClientCredential client;
    private String endUser;
    private String ip;

    public OpenApiContext(String requestId, OpenApiKey key, AppUser owner, String ip) {
        this.requestId = requestId;
        this.key = key;
        this.owner = owner;
        this.ip = ip;
    }

    public static OpenApiContext get() {
        return CURRENT.get();
    }

    public static void set(OpenApiContext ctx) {
        CURRENT.set(ctx);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public CurrentActor asActor() {
        UserRole role = UserRole.fromRaw(owner.getRole());
        return new CurrentActor(
                owner.getId(),
                owner.getUsername(),
                owner.getNickname(),
                role,
                owner.getAuthVersion(),
                owner.getStatus(),
                false
        );
    }

    public boolean hasScope(String scope) {
        List<String> scopes = key.getScopes();
        return scopes != null && scopes.contains(scope);
    }

    public String getRequestId() { return requestId; }
    public OpenApiKey getKey() { return key; }
    public AppUser getOwner() { return owner; }
    public ClientCredential getClient() { return client; }
    public void setClient(ClientCredential client) { this.client = client; }
    public String getEndUser() { return endUser; }
    public void setEndUser(String endUser) { this.endUser = endUser; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
}
