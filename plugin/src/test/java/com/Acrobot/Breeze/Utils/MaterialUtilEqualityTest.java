package com.Acrobot.Breeze.Utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Targets {@link MaterialUtil#isEmpty(ItemStack)} and
 * {@link MaterialUtil#equals(ItemStack, ItemStack)} — the existing
 * {@code MaterialTest} only exercises the name-shortening path.
 */
@ExtendWith(MockitoExtension.class)
class MaterialUtilEqualityTest {

    @Mock private ItemStack stack;

    @Test
    void isEmpty_trueForNull() {
        assertThat(MaterialUtil.isEmpty(null)).isTrue();
    }

    @Test
    void isEmpty_trueForAirMaterial() {
        lenient().when(stack.getType()).thenReturn(Material.AIR);
        assertThat(MaterialUtil.isEmpty(stack)).isTrue();
    }

    @Test
    void isEmpty_falseForRealItem() {
        // The impl checks (item == null || type == AIR); amount is irrelevant.
        lenient().when(stack.getType()).thenReturn(Material.STONE);
        assertThat(MaterialUtil.isEmpty(stack)).isFalse();
    }

    @Test
    void equals_returnsTrueForBothEmpty() {
        assertThat(MaterialUtil.equals(null, null)).isTrue();
    }

    @Test
    void equals_returnsFalseWhenOneIsEmpty() {
        ItemStack real = mock(ItemStack.class);
        lenient().when(real.getType()).thenReturn(Material.STONE);
        lenient().when(real.getAmount()).thenReturn(1);

        assertThat(MaterialUtil.equals(real, null)).isFalse();
        assertThat(MaterialUtil.equals(null, real)).isFalse();
    }

    @Test
    void equals_returnsFalseForDifferentMaterials() {
        ItemStack a = mock(ItemStack.class);
        ItemStack b = mock(ItemStack.class);
        lenient().when(a.getType()).thenReturn(Material.STONE);
        lenient().when(a.getAmount()).thenReturn(1);
        lenient().when(b.getType()).thenReturn(Material.DIRT);
        lenient().when(b.getAmount()).thenReturn(1);

        assertThat(MaterialUtil.equals(a, b)).isFalse();
    }

    @Test
    void equals_returnsTrueForSameMaterialAndNoMeta() {
        ItemStack a = mock(ItemStack.class);
        ItemStack b = mock(ItemStack.class);
        lenient().when(a.getType()).thenReturn(Material.STONE);
        lenient().when(a.getAmount()).thenReturn(1);
        lenient().when(a.hasItemMeta()).thenReturn(false);
        lenient().when(b.getType()).thenReturn(Material.STONE);
        lenient().when(b.getAmount()).thenReturn(1);
        lenient().when(b.hasItemMeta()).thenReturn(false);

        assertThat(MaterialUtil.equals(a, b)).isTrue();
    }

    @Test
    void equals_returnsFalseWhenOneSideHasMeta() {
        ItemStack plain = mock(ItemStack.class);
        ItemStack meta  = mock(ItemStack.class);
        ItemMeta itemMeta = mock(ItemMeta.class);

        lenient().when(plain.getType()).thenReturn(Material.STONE);
        lenient().when(plain.getAmount()).thenReturn(1);
        lenient().when(plain.hasItemMeta()).thenReturn(false);

        lenient().when(meta.getType()).thenReturn(Material.STONE);
        lenient().when(meta.getAmount()).thenReturn(1);
        lenient().when(meta.hasItemMeta()).thenReturn(true);
        lenient().when(meta.getItemMeta()).thenReturn(itemMeta);

        assertThat(MaterialUtil.equals(plain, meta)).isFalse();
    }
}
