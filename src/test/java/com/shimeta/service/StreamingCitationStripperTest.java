package com.shimeta.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingCitationStripperTest {

    private static String run(String... chunks) {
        StreamingCitationStripper stripper = new StreamingCitationStripper();
        StringBuilder out = new StringBuilder();
        for (String c : chunks) {
            out.append(stripper.push(c));
        }
        return out.append(stripper.flush()).toString();
    }

    @Test
    @DisplayName("去掉完整的引用角标，与 cleanAnswer 规则一致")
    void stripsMarkers() {
        assertThat(run("开机请长按电源键[1]，", "检查适配器【2】。¹²")).isEqualTo("开机请长按电源键，检查适配器。");
    }

    @Test
    @DisplayName("角标被拆在两段之间也能去掉")
    void stripsMarkersSplitAcrossChunks() {
        assertThat(run("长按电源键[", "1", "]三秒【", "12】。")).isEqualTo("长按电源键三秒。");
    }

    @Test
    @DisplayName("未闭合角标时先缓住，下一段确认不是角标再原样输出；普通方括号内容不受影响")
    void holdsPendingMarkerOnly() {
        StreamingCitationStripper stripper = new StreamingCitationStripper();
        assertThat(stripper.push("型号[")).isEqualTo("型号");
        assertThat(stripper.push("A1] 尺寸")).isEqualTo("[A1] 尺寸");
        assertThat(stripper.push("第[2")).isEqualTo("第");
        assertThat(stripper.flush()).isEqualTo("[2");
    }
}
