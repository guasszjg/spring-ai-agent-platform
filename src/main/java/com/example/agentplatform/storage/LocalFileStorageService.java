package com.example.agentplatform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 基于本地磁盘的对象存储服务实现
 * 适用环境：开发测试、轻量化私有部署
 */
@Service
public class LocalFileStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path rootPath;

    public LocalFileStorageService(@Value("${app.storage.local.root-dir:./data/kb-files}") String rootDir) {
        this.rootPath = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootPath);
            log.info("本地对象存储初始化就绪，根目录: {}", this.rootPath);
        } catch (IOException e) {
            log.error("创建本地对象存储根目录失败: {}", this.rootPath, e);
            throw new IllegalStateException("无法创建本地存储根目录: " + this.rootPath, e);
        }
    }

    public Path getRootPath() {
        return rootPath;
    }

    @Override
    public String putObject(String objectKey, InputStream inputStream, long size, String contentType) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream 不能为空");
        }

        Path target = resolveSafePath(objectKey);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(new BufferedInputStream(inputStream), md);
                 OutputStream os = new BufferedOutputStream(Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                dis.transferTo(os);
            }

            String sha256 = HexFormat.of().formatHex(md.digest());
            log.debug("本地对象存储写入成功: objectKey={}, sha256={}, target={}", objectKey, sha256, target);
            return sha256;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 摘要算法", e);
        } catch (IOException e) {
            log.error("写入本地对象存储失败: key={}", objectKey, e);
            throw new RuntimeException("写入本地对象失败: " + objectKey, e);
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        Path target = resolveSafePath(objectKey);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new IllegalArgumentException("对象不存在: " + objectKey);
        }
        try {
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new RuntimeException("读取本地对象失败: " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            Path target = resolveSafePath(objectKey);
            return Files.exists(target) && !Files.isDirectory(target);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            Path target = resolveSafePath(objectKey);
            Files.deleteIfExists(target);
            log.debug("已删除本地对象: {}", objectKey);
        } catch (IOException e) {
            log.warn("删除本地对象失败: key={}, err={}", objectKey, e.getMessage());
        }
    }

    @Override
    public long getObjectSize(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return -1L;
        }
        try {
            Path target = resolveSafePath(objectKey);
            if (Files.exists(target) && !Files.isDirectory(target)) {
                return Files.size(target);
            }
            return -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    Path resolveSafePath(String objectKey) {
        String sanitized = objectKey.replace('\\', '/').trim();
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }
        Path resolved = rootPath.resolve(sanitized).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new SecurityException("非法对象存储路径，拒绝跨目录访问: " + objectKey);
        }
        return resolved;
    }
}
