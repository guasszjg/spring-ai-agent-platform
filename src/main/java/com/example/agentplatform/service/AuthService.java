package com.example.agentplatform.service;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<LoginResponse> login(String username, String password) {
        return userRepository.findByUsernameIgnoreCase(username)
                .filter(user -> passwordEncoder.matches(password, user.getPassword()))
                .map(this::toLoginResponse);
    }

    private LoginResponse toLoginResponse(AppUser user) {
        return new LoginResponse(user.getUsername(), user.getNickname(), user.getRole(), user.getAvatar());
    }
}
