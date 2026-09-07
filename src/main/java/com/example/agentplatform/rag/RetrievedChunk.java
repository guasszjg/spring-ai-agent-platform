package com.example.agentplatform.rag;

public record RetrievedChunk(String content, String sourceName, Double score) {
}
