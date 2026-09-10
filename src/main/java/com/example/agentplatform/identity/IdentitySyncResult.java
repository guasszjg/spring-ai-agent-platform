package com.example.agentplatform.identity;

public record IdentitySyncResult(boolean success, String externalId, String displayName, String attributes, String error) {
    public static IdentitySyncResult ok(String externalId, String displayName, String attributes) {
        return new IdentitySyncResult(true, externalId, displayName, attributes, null);
    }

    public static IdentitySyncResult fail(String error) {
        return new IdentitySyncResult(false, null, null, null, error);
    }
}
