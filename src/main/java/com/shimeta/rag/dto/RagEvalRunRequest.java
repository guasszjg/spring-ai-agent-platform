package com.shimeta.rag.dto;

/** 运行一次评测。除 label 外，留空的项使用知识库保存的设置。 */
public class RagEvalRunRequest {
    private String label;
    private String searchMethod;
    private String rerankMode;
    private Double vectorWeight;
    private Double keywordWeight;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getSearchMethod() { return searchMethod; }
    public void setSearchMethod(String searchMethod) { this.searchMethod = searchMethod; }

    public String getRerankMode() { return rerankMode; }
    public void setRerankMode(String rerankMode) { this.rerankMode = rerankMode; }

    public Double getVectorWeight() { return vectorWeight; }
    public void setVectorWeight(Double vectorWeight) { this.vectorWeight = vectorWeight; }

    public Double getKeywordWeight() { return keywordWeight; }
    public void setKeywordWeight(Double keywordWeight) { this.keywordWeight = keywordWeight; }
}
