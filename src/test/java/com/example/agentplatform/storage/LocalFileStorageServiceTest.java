package com.example.agentplatform.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalFileStorageService(tempDir.toString());
    }

    @Test
    void putAndGetObject_storesAndRetrievesContentWithSha256() throws Exception {
        String content = "Spring AI 自研 RAG 测试文档内容";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        String objectKey = "kb-1001/doc-2001/test.txt";
        String sha256 = storageService.putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain");

        assertThat(sha256).isNotBlank();
        assertThat(sha256).hasSize(64);
        assertThat(storageService.exists(objectKey)).isTrue();
        assertThat(storageService.getObjectSize(objectKey)).isEqualTo(bytes.length);

        try (InputStream is = storageService.getObject(objectKey)) {
            String readBack = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(readBack).isEqualTo(content);
        }

        storageService.deleteObject(objectKey);
        assertThat(storageService.exists(objectKey)).isFalse();
    }

    @Test
    void resolveSafePath_rejectsDirectoryTraversal() {
        assertThatThrownBy(() -> storageService.resolveSafePath("../../../etc/passwd"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("非法对象存储路径");

        assertThatThrownBy(() -> storageService.resolveSafePath("kb-1/../../secret.key"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("非法对象存储路径");
    }

    @Test
    void getObject_nonExistingObject_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> storageService.getObject("non-existing-file.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对象不存在");
    }
}
