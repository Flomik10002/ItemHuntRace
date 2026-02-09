package com.redlimerl.speedrunigt.mixins.screen;

import com.redlimerl.speedrunigt.race.RaceLobbyScreen;
import com.redlimerl.speedrunigt.utils.ButtonWidgetHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int x = this.width / 2 - 100;
        int y = this.height / 4 + 48 + 24 * 5;
        this.addDrawableChild(ButtonWidgetHelper.create(
                x, y, 200, 20,
                Text.translatable("speedrunigt.race.button"),
                button -> {
                    if (this.client != null) this.client.setScreen(new RaceLobbyScreen(this));
                }
        ));
    }
}
