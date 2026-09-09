package com.example.agentplatform.security;

import com.example.agentplatform.config.SessionAuthInterceptor;
import com.example.agentplatform.model.*;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.service.AuthService;
import com.example.agentplatform.service.UserAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityControlsRegressionTest {

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("临时密码过期：登录与改密时严格拒绝过期临时密码")
    void temporaryPasswordExpirationEnforced() {
        AuthService authService = new AuthService(userRepository, passwordEncoder, new ObjectMapper());

        AppUser expiredUser = new AppUser();
        expiredUser.setId("user-expired");
        expiredUser.setUsername("alice");
        expiredUser.setPassword(passwordEncoder.encode("Temp123456"));
        expiredUser.setRole("DEVELOPER");
        expiredUser.setStatus(UserStatus.PENDING_PASSWORD);
        expiredUser.setMustChangePassword(true);
        // 设置为 1 小时前已过期
        expiredUser.setTempPasswordExpiresAt(LocalDateTime.now().minusHours(1));

        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(expiredUser));

        // 1. 尝试登录被拦截
        assertThatThrownBy(() -> authService.login("alice", "Temp123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("临时密码已过期");

        // 2. 尝试修改密码被拦截
        ChangePasswordRequest changeReq = new ChangePasswordRequest();
        changeReq.setOldPassword("Temp123456");
        changeReq.setNewPassword("NewSecurePassword888");

        assertThatThrownBy(() -> authService.changePassword("alice", changeReq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("临时密码已过期");
    }

    @Test
    @DisplayName("CSRF 保护拦截器：无 Token 或 Token 伪造的变更请求严格阻断 (403)，合法 Token 正常放行")
    void csrfProtectionEnforcedOnMutatingRequests() throws Exception {
        SessionAuthInterceptor interceptor = new SessionAuthInterceptor(userRepository);

        AppUser dbUser = new AppUser();
        dbUser.setId("u-1");
        dbUser.setUsername("bob");
        dbUser.setRole("DEVELOPER");
        dbUser.setStatus(UserStatus.ACTIVE);
        dbUser.setAuthVersion(1);
        dbUser.setMustChangePassword(false);

        when(userRepository.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(dbUser));

        LoginResponse sessionUser = new LoginResponse();
        sessionUser.setId("u-1");
        sessionUser.setUsername("bob");
        sessionUser.setRole("DEVELOPER");
        sessionUser.setAuthVersion(1);

        String validCsrfToken = "valid-csrf-token-123456";

        // 场景 1: POST 请求未携带 X-CSRF-TOKEN -> 403
        {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/agents");
            req.getSession().setAttribute(SessionAuthInterceptor.SESSION_USER, sessionUser);
            req.getSession().setAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR, validCsrfToken);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());
            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(403);
            assertThat(resp.getContentAsString()).contains("CSRF 凭证校验失败");
        }

        // 场景 2: DELETE 请求携带伪造的 X-CSRF-TOKEN -> 403
        {
            MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/agents/agent-1");
            req.getSession().setAttribute(SessionAuthInterceptor.SESSION_USER, sessionUser);
            req.getSession().setAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR, validCsrfToken);
            req.addHeader("X-CSRF-TOKEN", "attacker-forged-token");
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());
            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(403);
            assertThat(resp.getContentAsString()).contains("CSRF 凭证校验失败");
        }

        // 场景 3: PUT 请求携带合法的 X-CSRF-TOKEN -> 放行
        {
            MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/agents/agent-1");
            req.getSession().setAttribute(SessionAuthInterceptor.SESSION_USER, sessionUser);
            req.getSession().setAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR, validCsrfToken);
            req.addHeader("X-CSRF-TOKEN", validCsrfToken);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());
            assertThat(result).isTrue();
            assertThat(resp.getStatus()).isEqualTo(200);
        }

        // 场景 4: GET 安全读取请求无需 CSRF 校验 -> 放行
        {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            req.getSession().setAttribute(SessionAuthInterceptor.SESSION_USER, sessionUser);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());
            assertThat(result).isTrue();
        }
    }

    @Test
    @DisplayName("最后管理员并发保护：严禁禁用或降级唯一的活跃超级管理员")
    void lastActiveSuperAdminConcurrencyProtection() {
        UserAdminService adminService = new UserAdminService(userRepository, passwordEncoder);

        AppUser soleAdmin = new AppUser();
        soleAdmin.setId("admin-sole");
        soleAdmin.setUsername("superadmin");
        soleAdmin.setRole(UserRole.SUPER_ADMIN.getCode());
        soleAdmin.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById("admin-sole")).thenReturn(Optional.of(soleAdmin));
        // 悲观行锁查询返回当前系统中仅有这 1 名处于激活状态的超级管理员
        when(userRepository.findActiveSuperAdminsForUpdate()).thenReturn(List.of(soleAdmin));

        // 1. 尝试停用唯一超管 -> 拒绝
        assertThatThrownBy(() -> adminService.changeUserStatus("admin-sole", UserStatus.DISABLED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("系统必须保留至少一名正常状态的超级管理员");

        // 2. 尝试将唯一超管降级为普通开发者 -> 拒绝
        assertThatThrownBy(() -> adminService.changeUserRole("admin-sole", UserRole.DEVELOPER.getCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("系统必须保留至少一名正常状态的超级管理员");

        // 3. 当存在至少两名激活状态超级管理员时，允许停用其中一名
        AppUser admin2 = new AppUser();
        admin2.setId("admin-2");
        admin2.setUsername("admin2");
        admin2.setRole(UserRole.SUPER_ADMIN.getCode());
        admin2.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById("admin-2")).thenReturn(Optional.of(admin2));
        when(userRepository.findActiveSuperAdminsForUpdate()).thenReturn(List.of(soleAdmin, admin2));
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserSummaryDto updated = adminService.changeUserStatus("admin-2", UserStatus.DISABLED);
        assertThat(updated.getStatus()).isEqualTo(UserStatus.DISABLED);
    }
}
