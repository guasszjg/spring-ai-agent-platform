package com.example.agentplatform.service;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .filter(user -> passwordEncoder.matches(password, user.getPassword()))
                .map(this::toLoginResponse);
    }

    public Optional<LoginResponse> currentUser(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByUsernameIgnoreCase(username).map(this::toLoginResponse);
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

    private LoginResponse toLoginResponse(AppUser user) {
        return new LoginResponse(
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getAvatar(),
                sanitizePreferences(parsePreferences(user.getUiPreferences()))
        );
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
