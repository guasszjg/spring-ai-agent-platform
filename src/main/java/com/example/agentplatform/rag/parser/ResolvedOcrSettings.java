package com.example.agentplatform.rag.parser;

public record ResolvedOcrSettings(
        String provider,
        boolean enabled,
        String endpoint,
        String apiKey,
        String modelName,
        int timeoutSeconds
) {
    public static boolean isOnlineProvider(String provider) {
        if (provider == null) {
            return false;
        }
        String p = provider.trim().toUpperCase();
        return p.contains("ONLINE") || p.contains("CLOUD") || "CLOUD_API".equals(p);
    }

    public boolean online() {
        return isOnlineProvider(provider);
    }
}
