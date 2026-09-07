package com.example.agentplatform.rag;

import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库 RAG 引擎底层适配器接口
 * 采用策略模式：目前落地 Dify 外挂实现，后续拓展 Spring AI 原生 RAG 时只需增加 SpringAiNativeKnowledgeBaseProvider
 */
public interface KnowledgeBaseProvider {

    /**
     * 引擎类型唯一标识，例如 "DIFY"、"SPRING_AI"
     */
    String getProviderType();

    /**
     * RAG 引擎访问地址（不含密钥）。未配置时返回空串。
     */
    default String getBaseUrl() {
        return "";
    }

    /**
     * 在底层 RAG 引擎创建知识库数据集（支持 Embedding 向量模型、检索模型与权重配置）
     */
    DifyDatasetDto createDataset(String name, String description, String indexingTechnique, String permission,
                                 String embeddingModel, String embeddingProvider, String searchMethod, Integer topK, Boolean rerankEnabled,
                                 String rerankMode, String rerankModel, String rerankModelProvider,
                                 Double vectorWeight, Double keywordWeight);

    /**
     * 更新知识库数据集信息与检索模型/权重配置
     */
    void updateDataset(String externalDatasetId, String name, String description, String searchMethod, Integer topK, Boolean rerankEnabled,
                       String rerankMode, String rerankModel, String rerankModelProvider,
                       Double vectorWeight, Double keywordWeight);

    /**
     * 在底层引擎删除知识库数据集
     */
    void deleteDataset(String externalDatasetId);

    /**
     * 从底层引擎获取所有数据集列表
     */
    List<DifyDatasetDto> listExternalDatasets();

    /**
     * 向底层知识库上传文档文件
     */
    DifyDocumentDto uploadDocument(String externalDatasetId, MultipartFile file);

    /**
     * 在底层知识库删除文档
     */
    void deleteDocument(String externalDatasetId, String externalDocId);

    /**
     * 查询知识库内的文档列表及最新切片索引状态
     */
    List<DifyDocumentDto> listDocuments(String externalDatasetId, int page, int limit);

    /**
     * 将 FAQ 问答对同步到底层引擎以参与语义向量召回
     */
    DifyDocumentDto syncFaqDocument(String externalDatasetId, String existingExternalDocId, String question, String answer, String category);

    /**
     * 在底层引擎中删除 FAQ 文档
     */
    void deleteFaqDocument(String externalDatasetId, String externalDocId);

    /**
     * 按用户问题检索切片，供智能体对话注入上下文
     */
    List<RetrievedChunk> retrieve(String externalDatasetId, String query);
}
