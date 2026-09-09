package com.example.agentplatform.service;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ChangePasswordRequest;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.model.UpdateUserProfileRequest;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AuthService {

    private static final Set<String> KB_LAYOUTS = Set.of("card", "table");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    public Optional<LoginResponse> login(String username, String password) {
        return userRepository.findByUsernameIgnoreCase(username)
                .filter(user -> {
                    if (UserStatus.DISABLED.equals(user.getStatus())) {
                        throw new IllegalStateException("账号已被管理员禁用，请联系超级管理员");
                    }
                    if (!passwordEncoder.matches(password, user.getPassword())) {
                        return false;
                    }
                    if (Boolean.TRUE.equals(user.getMustChangePassword())
                            && user.getTempPasswordExpiresAt() != null
                            && LocalDateTime.now().isAfter(user.getTempPasswordExpiresAt())) {
                        throw new IllegalStateException("临时密码已过期，请联系管理员重新重置密码");
                    }
                    return true;
                })
                .map(this::toLoginResponse);
    }

    public Optional<LoginResponse> currentUser(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByUsernameIgnoreCase(username)
                .filter(user -> !UserStatus.DISABLED.equals(user.getStatus()))
                .map(this::toLoginResponse);
    }

    @Transactional
    public LoginResponse changePassword(String username, ChangePasswordRequest request) {
        AppUser user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (UserStatus.DISABLED.equals(user.getStatus())) {
            throw new IllegalStateException("账号已被禁用");
        }

        if (Boolean.TRUE.equals(user.getMustChangePassword())
                && user.getTempPasswordExpiresAt() != null
                && LocalDateTime.now().isAfter(user.getTempPasswordExpiresAt())) {
            throw new IllegalStateException("临时密码已过期，请联系管理员重新重置密码");
        }

        if (request.getNewPassword() == null || request.getNewPassword().trim().length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于 6 位");
        }

        // 如果不是强制首次改密，必须校验原密码
        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            if (request.getOldPassword() == null || !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
                throw new IllegalArgumentException("原密码校验失败");
            }
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword().trim()));
        user.setMustChangePassword(false);
        user.setStatus(UserStatus.ACTIVE);
        user.setTempPasswordExpiresAt(null);
        user.setAuthVersion((user.getAuthVersion() == null ? 1 : user.getAuthVersion()) + 1);
        user.setUpdatedAt(LocalDateTime.now());
        AppUser saved = userRepository.save(user);

        return toLoginResponse(saved);
    }

    @Transactional
    public LoginResponse updateProfile(String username, UpdateUserProfileRequest request) {
        AppUser user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (request.getNickname() != null && !request.getNickname().trim().isBlank()) {
            user.setNickname(request.getNickname().trim());
        }
        if (request.getAvatar() != null && !request.getAvatar().trim().isBlank()) {
            user.setAvatar(request.getAvatar().trim());
        }
        user.setUpdatedAt(LocalDateTime.now());
        AppUser saved = userRepository.save(user);
        return toLoginResponse(saved);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPreferences(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .map(user -> sanitizePreferences(parsePreferences(user.getUiPreferences())))
                .orElseGet(LinkedHashMap::new);
    }

    @Transactional
    public Map<String, Object> savePreferences(String username, Map<String, Object> patch) {
        AppUser user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        Map<String, Object> merged = parsePreferences(user.getUiPreferences());
        if (patch != null) {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        merged = sanitizePreferences(merged);
        try {
            user.setUiPreferences(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            throw new IllegalStateException("保存界面偏好失败");
        }
        userRepository.save(user);
        return merged;
    }

    public LoginResponse toLoginResponse(AppUser user) {
        UserRole userRole = UserRole.fromRaw(user.getRole());
        LoginResponse response = new LoginResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setRole(userRole.getCode());
        response.setRoleName(userRole.getName());
        response.setStatus(user.getStatus() != null ? user.getStatus() : UserStatus.ACTIVE);
        response.setAuthVersion(user.getAuthVersion() != null ? user.getAuthVersion() : 1);
        response.setMustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()));
        response.setAvatar(user.getAvatar());
        response.setPermissions(userRole.getPermissions());
        response.setPreferences(sanitizePreferences(parsePreferences(user.getUiPreferences())));
        return response;
    }

    private Map<String, Object> parsePreferences(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> sanitizePreferences(Map<String, Object> preferences) {
        Map<String, Object> safe = preferences != null ? new LinkedHashMap<>(preferences) : new LinkedHashMap<>();
        Object layout = safe.get("kbViewLayout");
        if (layout != null && !KB_LAYOUTS.contains(String.valueOf(layout))) {
            safe.remove("kbViewLayout");
        }
        return safe;
    }
}
