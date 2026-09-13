package com.example.agentplatform.storage;

import java.io.InputStream;

/**
 * 知识库源文件与解析产物对象存储抽象契约
 * 解耦底层存储介质（本地磁盘、MinIO、S3/OSS 等）
 */
public interface ObjectStorageService {

    /**
     * 存储对象，并返回计算得到的 SHA-256 摘要
     *
     * @param objectKey   对象存储唯一路径 key (例如: kb-123/doc-456/manual.pdf)
     * @param inputStream 输入流
     * @param size        文件字节大小（若未知可传 -1）
     * @param contentType 媒体类型
     * @return SHA-256 十六进制摘要
     */
    String putObject(String objectKey, InputStream inputStream, long size, String contentType);

    /**
     * 获取对象数据流
     *
     * @param objectKey 对象路径 key
     * @return 输入流
     */
    InputStream getObject(String objectKey);

    /**
     * 检查对象是否存在
     *
     * @param objectKey 对象路径 key
     * @return 是否存在
     */
    boolean exists(String objectKey);

    /**
     * 删除对象
     *
     * @param objectKey 对象路径 key
     */
    void deleteObject(String objectKey);

    /**
     * 获取对象字节大小
     *
     * @param objectKey 对象路径 key
     * @return 字节数（若不存在返回 -1）
     */
    long getObjectSize(String objectKey);
}
