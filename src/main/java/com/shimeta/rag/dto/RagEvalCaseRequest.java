package com.shimeta.rag.dto;

import java.util.List;

/** 一道评测题。答案优先按 expectedTexts（原文片段）判断，没有时才按文档或切片 ID 判断。 */
public class RagEvalCaseRequest {
    private String query;
    private List<String> expectedTexts;
    private List<String> expectedDocumentIds;
    private List<String> expectedChunkIds;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<String> getExpectedTexts() { return expectedTexts; }
    public void setExpectedTexts(List<String> expectedTexts) { this.expectedTexts = expectedTexts; }

    public List<String> getExpectedDocumentIds() { return expectedDocumentIds; }
    public void setExpectedDocumentIds(List<String> expectedDocumentIds) { this.expectedDocumentIds = expectedDocumentIds; }

    public List<String> getExpectedChunkIds() { return expectedChunkIds; }
    public void setExpectedChunkIds(List<String> expectedChunkIds) { this.expectedChunkIds = expectedChunkIds; }
}
