package com.Acrobot.Breeze.Utils;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class NameUtilTest {

    @Mock private Player player;

    @Test
    void getUUID_byPlayer_delegatesToGetUniqueId() {
        UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        lenient().when(player.getUniqueId()).thenReturn(id);

        assertThat(NameUtil.getUUID(player)).isEqualTo(id);
    }
}
