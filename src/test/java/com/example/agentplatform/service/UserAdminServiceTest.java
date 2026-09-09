package com.example.agentplatform.service;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.CreateUserRequest;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.model.UserSummaryDto;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAdminServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        service = new UserAdminService(userRepository, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        CurrentActor.clear();
    }

    @Test
    void createUserSuccessfully() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("dev_alex");
        req.setNickname("亚历克斯");
        req.setRole("DEVELOPER");

        when(userRepository.existsByUsernameIgnoreCase("dev_alex")).thenReturn(false);
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.createUser(req);

        assertThat(result).containsKey("user");
        assertThat(result).containsKey("tempPassword");
        UserSummaryDto dto = (UserSummaryDto) result.get("user");
        assertThat(dto.getUsername()).isEqualTo("dev_alex");
        assertThat(dto.getRole()).isEqualTo("DEVELOPER");
        assertThat(dto.getStatus()).isEqualTo(UserStatus.PENDING_PASSWORD);
        assertThat(dto.getMustChangePassword()).isTrue();
    }

    @Test
    void createUserFailsOnDuplicateUsername() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("existing_user");

        when(userRepository.existsByUsernameIgnoreCase("existing_user")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void cannotDowngradeLastSuperAdmin() {
        AppUser admin = new AppUser();
        admin.setId("u-admin");
        admin.setUsername("admin");
        admin.setRole(UserRole.SUPER_ADMIN.getCode());
        admin.setStatus(UserStatus.ACTIVE);
        admin.setAuthVersion(1);

        when(userRepository.findById("u-admin")).thenReturn(Optional.of(admin));
        when(userRepository.findAll()).thenReturn(List.of(admin));

        CurrentActor.set(new CurrentActor("u-other", "other", "Other", UserRole.SUPER_ADMIN, 1, UserStatus.ACTIVE, false));

        assertThatThrownBy(() -> service.changeUserRole("u-admin", "DEVELOPER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("系统必须保留至少一名正常状态的超级管理员");
    }

    @Test
    void cannotDisableCurrentLoggedInActor() {
        AppUser admin = new AppUser();
        admin.setId("u-admin");
        admin.setUsername("admin");
        admin.setRole(UserRole.SUPER_ADMIN.getCode());
        admin.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById("u-admin")).thenReturn(Optional.of(admin));

        CurrentActor.set(new CurrentActor("u-admin", "admin", "Admin", UserRole.SUPER_ADMIN, 1, UserStatus.ACTIVE, false));

        assertThatThrownBy(() -> service.changeUserStatus("u-admin", UserStatus.DISABLED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止禁用当前登录的操作员账号");
    }

    @Test
    void resetPasswordWithCustomPassword() {
        AppUser user = new AppUser();
        user.setId("u-test");
        user.setUsername("testuser");
        user.setPassword("oldpass");
        user.setAuthVersion(1);

        when(userRepository.findById("u-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.resetPassword("u-test", "newSecret123", false);

        assertThat(result.get("tempPassword")).isEqualTo("newSecret123");
        assertThat(result.get("mustChangePassword")).isEqualTo(false);
        assertThat(user.getMustChangePassword()).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getAuthVersion()).isEqualTo(2);
    }

    @Test
    void resetPasswordWithGeneratedPassword() {
        AppUser user = new AppUser();
        user.setId("u-test2");
        user.setUsername("testuser2");
        user.setPassword("oldpass");
        user.setAuthVersion(1);

        when(userRepository.findById("u-test2")).thenReturn(Optional.of(user));
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.resetPassword("u-test2", null, null);

        assertThat(result.get("tempPassword")).isNotNull();
        assertThat(result.get("mustChangePassword")).isEqualTo(true);
        assertThat(user.getMustChangePassword()).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_PASSWORD);
        assertThat(user.getAuthVersion()).isEqualTo(2);
    }
}
