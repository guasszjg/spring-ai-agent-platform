package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.config.OutboundUrlValidator;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.model.PlatformTool;
import com.example.agentplatform.model.PlatformToolUpdateRequest;
import com.example.agentplatform.model.PlatformToolView;
import com.example.agentplatform.repository.PlatformToolRepository;
import com.example.agentplatform.tool.AgentToolRegistry;
import com.example.agentplatform.tool.PlatformToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformToolServiceTest {

    @Test
    void updateEncryptsBochaKeyAndExposesMaskedView() {
        PlatformToolRepository repository = mock(PlatformToolRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        PlatformToolService service = new PlatformToolService(repository, crypto, new ObjectMapper(), registry, mock(com.example.agentplatform.config.OutboundUrlValidator.class));

        PlatformTool entity = new PlatformTool();
        entity.setId(PlatformToolService.BOCHA_TOOL_ID);
        entity.setCode(PlatformToolService.BOCHA_TOOL_CODE);
        entity.setName("联网检索");
        entity.setTitle("Bocha Web Search");
        entity.setPrefix("bocha");
        entity.setCustomIcon("bocha");
        entity.setEnabled(true);
        when(repository.findById(PlatformToolService.BOCHA_TOOL_ID)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PlatformToolUpdateRequest req = new PlatformToolUpdateRequest();
        req.setApiKey("sk-platform-bocha-key");
        req.setConfig(Map.of("count", 8, "freshness", "oneDay", "summary", true));

        PlatformToolView view = service.update(PlatformToolService.BOCHA_TOOL_ID, req);
        assertThat(entity.getApiKeyEncrypted()).startsWith("enc:v1:");
        assertThat(entity.getApiKeyEncrypted()).doesNotContain("sk-platform-bocha-key");
        assertThat(view.isHasApiKey()).isTrue();
        assertThat(view.getApiKeyMasked()).contains("...");
        assertThat(view.getConfig().get("count")).isEqualTo(8);
        assertThat(service.getBochaApiKey()).isEqualTo("sk-platform-bocha-key");
    }

    @Test
    void listSeedsSixBuiltinToolsWhenTableEmpty() {
        PlatformToolRepository repository = mock(PlatformToolRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        PlatformToolService service = new PlatformToolService(repository, crypto, new ObjectMapper(), registry, mock(com.example.agentplatform.config.OutboundUrlValidator.class));

        when(repository.existsById(any())).thenReturn(false);
        when(repository.findByCode(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(PlatformToolCatalog.builtins());

        assertThat(service.list()).hasSize(6);
        assertThat(service.list())
                .extracting(PlatformToolView::getName)
                .contains("时区转换", "时间戳转换", "获取当前时间", "获取时间戳", "星期几计算器", "联网检索");
    }

    @Test
    void createCustomHttpToolThenPageAndRejectBuiltinDelete() {
        PlatformToolRepository repository = mock(PlatformToolRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        OutboundUrlValidator urlValidator = mock(OutboundUrlValidator.class);
        doNothing().when(urlValidator).validateProviderBaseUrl(any());
        PlatformToolService service = new PlatformToolService(repository, crypto, new ObjectMapper(), registry, urlValidator);

        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PlatformToolUpdateRequest req = new PlatformToolUpdateRequest();
        req.setName("订单查询");
        req.setDescription("当用户询问订单状态时调用");
        req.setConfig(Map.of("url", "https://api.example.com/orders", "method", "POST"));

        PlatformToolView created = service.create(req);
        assertThat(created.getBuiltin()).isFalse();
        assertThat(created.getCategory()).isEqualTo("CUSTOM");
        assertThat(created.getCode()).startsWith("http_");
        assertThat(created.getConfig().get("url")).isEqualTo("https://api.example.com/orders");

        List<PlatformTool> stored = new ArrayList<>(PlatformToolCatalog.builtins());
        PlatformTool custom = new PlatformTool();
        custom.setId(created.getId());
        custom.setCode(created.getCode());
        custom.setName(created.getName());
        custom.setTitle(created.getTitle());
        custom.setPrefix("http");
        custom.setCategory("CUSTOM");
        custom.setEnabled(true);
        custom.setBuiltin(false);
        custom.setConfigJson("{\"url\":\"https://api.example.com/orders\",\"method\":\"POST\"}");
        stored.add(custom);
        when(repository.existsById(any())).thenReturn(true);
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(stored);

        PageResult<PlatformToolView> first = service.page(null, null, 1, 6);
        assertThat(first.getTotal()).isEqualTo(7);
        assertThat(first.getRecords()).hasSize(6);
        PageResult<PlatformToolView> second = service.page(null, "CUSTOM", 1, 6);
        assertThat(second.getTotal()).isEqualTo(1);
        assertThat(second.getRecords().get(0).getName()).isEqualTo("订单查询");

        PlatformTool builtin = PlatformToolCatalog.builtins().get(0);
        when(repository.findById(builtin.getId())).thenReturn(Optional.of(builtin));
        assertThatThrownBy(() -> service.delete(builtin.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内置工具不能删除");
    }
}
