package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.RetrievedChunk;

import java.util.List;

/**
 * 引擎无关的物理 RAG 引擎适配接口
 * 供 Dify 适配器与 Spring AI 自研适配器实现
 */
public interface RagEngineProvider {

    /**
     * 所属引擎类型
     */
    EngineType getEngineType();

    /**
     * 查询引擎特性与支持矩阵
     */
    EngineCapabilities getCapabilities();

    /**
     * 根据索引规约创建或绑定物理索引
     *
     * @param spec 规约配置
     * @return 物理索引句柄
     */
    EngineIndexHandle createOrBindIndex(IndexBuildSpec spec);

    /**
     * 删除物理索引
     *
     * @param handleId 索引句柄 ID
     */
    void deleteIndex(String handleId);

    /**
     * 执行统一检索
     *
     * @param handleId 物理索引句柄 ID
     * @param request  检索请求参数
     * @return 命中切片列表
     */
    List<RetrievedChunk> retrieve(String handleId, RetrievalRequest request);
}
