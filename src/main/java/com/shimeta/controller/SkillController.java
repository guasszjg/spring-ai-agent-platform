package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.model.EnabledToggleRequest;
import com.shimeta.security.CurrentActor;
import com.shimeta.skill.SkillRequest;
import com.shimeta.skill.SkillService;
import com.shimeta.skill.SkillVersion;
import com.shimeta.skill.SkillView;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 技能库：技能的增删改查、版本、导入导出 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService service;

    public SkillController(SkillService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SkillView>>> list() {
        return run(null, () -> service.list(CurrentActor.get()));
    }

    /** 编辑页用到的上限，前端据此提示 */
    @GetMapping("/limits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxContentChars", SkillService.MAX_CONTENT_CHARS);
        limits.put("softContentChars", SkillService.SOFT_CONTENT_CHARS);
        limits.put("maxSkillsPerAgent", SkillService.MAX_SKILLS_PER_AGENT);
        return ResponseEntity.ok(ApiResponse.ok(limits));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SkillView>> get(@PathVariable String id) {
        return run(null, () -> service.get(id, CurrentActor.get()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SkillView>> create(@RequestBody SkillRequest request) {
        return run("技能已创建", () -> service.create(request, CurrentActor.get()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SkillView>> update(@PathVariable String id, @RequestBody SkillRequest request) {
        return run("技能已保存", () -> service.update(id, request, CurrentActor.get()));
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<ApiResponse<SkillView>> setEnabled(@PathVariable String id, @RequestBody EnabledToggleRequest request) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.getEnabled());
        return run(enabled ? "技能已启用" : "技能已停用", () -> service.setEnabled(id, enabled, CurrentActor.get()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        return run("技能已删除", () -> {
            service.delete(id, CurrentActor.get());
            return null;
        });
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<ApiResponse<List<SkillVersion>>> versions(@PathVariable String id) {
        return run(null, () -> service.versions(id, CurrentActor.get()));
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public ResponseEntity<ApiResponse<SkillView>> restore(@PathVariable String id, @PathVariable int version) {
        return run("已恢复到第 " + version + " 版", () -> service.restore(id, version, CurrentActor.get()));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<?> export(@PathVariable String id) {
        try {
            String text = service.export(id, CurrentActor.get());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("SKILL.md").build().toString())
                    .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                    .body(text);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    /** 导入 SKILL.md 或包含 SKILL.md 的 zip 压缩包 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SkillService.ImportResult>> importSkill(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return run(null, () -> {
            try {
                return service.importFile(file.getOriginalFilename(), file.getBytes(), overwrite, CurrentActor.get());
            } catch (IOException e) {
                throw new IllegalArgumentException("文件读取失败：" + e.getMessage());
            }
        }, result -> result.updated() ? "已导入为「" + result.skill().name() + "」的新版本" : "技能已导入");
    }

    private <T> ResponseEntity<ApiResponse<T>> run(String message, Supplier<T> action) {
        return run(message, action, null);
    }

    private <T> ResponseEntity<ApiResponse<T>> run(String message, Supplier<T> action,
                                                   java.util.function.Function<T, String> messageOf) {
        if (CurrentActor.get() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("请先登录"));
        }
        try {
            T data = action.get();
            String msg = messageOf != null ? messageOf.apply(data) : message;
            return ResponseEntity.ok(msg != null ? ApiResponse.ok(msg, data) : ApiResponse.ok(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }
}
