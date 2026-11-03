package com.shimeta.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单次回答最多调用工具轮数：默认 5，范围 1～10 */
class PlatformSettingServiceTest {

    @Test
    @DisplayName("未设置时默认 5 轮；库里的值超出范围时按边界取值")
    void defaultsAndClamps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());
        assertThat(new PlatformSettingService(jdbc).maxToolRounds()).isEqualTo(5);

        JdbcTemplate tooBig = mock(JdbcTemplate.class);
        when(tooBig.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of("99"));
        assertThat(new PlatformSettingService(tooBig).maxToolRounds()).isEqualTo(10);
    }

    @Test
    @DisplayName("保存后立即生效；超出 1～10 拒绝且不写库")
    void setValidatesAndCaches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformSettingService service = new PlatformSettingService(jdbc);

        service.setMaxToolRounds(8);
        assertThat(service.maxToolRounds()).isEqualTo(8);

        assertThatThrownBy(() -> service.setMaxToolRounds(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setMaxToolRounds(11)).isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, org.mockito.Mockito.times(1)).update(anyString(), any(Object[].class));
        verify(jdbc, never()).queryForList(anyString(), eq(String.class), any());
    }
}
