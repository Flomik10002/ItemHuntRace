package com.redlimerl.speedrunigt.race;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class TargetItemHudOverlay {
    private static Identifier cachedItemId = null;
    private static ItemStack cachedStack = ItemStack.EMPTY;

    private TargetItemHudOverlay() {}

    public static void render(MinecraftClient client, DrawContext context) {
        RaceSessionManager race = RaceSessionManager.getInstance();
        if (client.world == null || client.options.hudHidden || !race.shouldRenderTargetHud()) return;

        Identifier itemId = race.getTargetItemId().orElse(null);
        if (itemId == null) return;

        if (!itemId.equals(cachedItemId)) {
            cachedItemId = itemId;
            cachedStack = new ItemStack(Registries.ITEM.get(itemId));
        }

        int x = 6;
        int y = 6;
        Text name = cachedStack.getName();

        context.drawItem(cachedStack, x, y);
        context.drawTextWithShadow(client.textRenderer, name, x + 20, y + 6, 0xFFFFFFFF);
    }
}
