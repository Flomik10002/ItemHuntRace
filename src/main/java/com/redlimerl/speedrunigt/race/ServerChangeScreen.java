package com.redlimerl.speedrunigt.race;

import com.redlimerl.speedrunigt.utils.ButtonWidgetHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;

@Environment(EnvType.CLIENT)
public class ServerChangeScreen extends Screen {
    private final Screen parent;
    private final RaceSessionManager raceManager;
    private TextFieldWidget serverUrlField;

    public ServerChangeScreen(Screen parent, RaceSessionManager raceManager) {
        super(Text.literal("Change Server"));
        this.parent = parent;
        this.raceManager = raceManager;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Server URL input field
        this.serverUrlField = new TextFieldWidget(this.textRenderer, centerX - 150, centerY - 10, 300, 20, Text.literal("Server URL"));
        this.serverUrlField.setText(raceManager.getServerUri().toString());
        this.serverUrlField.setMaxLength(200);
        this.addDrawableChild(this.serverUrlField);

        // Save button
        this.addDrawableChild(ButtonWidgetHelper.create(centerX - 155, centerY + 30, 100, 20, 
                Text.literal("SAVE").formatted(Formatting.GREEN), button -> {
            String url = serverUrlField.getText().trim();
            if (!url.isEmpty()) {
                try {
                    URI uri = URI.create(url);
                    raceManager.setServerUri(uri);
                    if (this.client != null) {
                        this.client.setScreen(parent);
                    }
                } catch (Exception e) {
                    // Invalid URL - show error or ignore
                }
            }
        }));

        // Reset to default button
        this.addDrawableChild(ButtonWidgetHelper.create(centerX - 50, centerY + 30, 100, 20, 
                Text.literal("RESET").formatted(Formatting.YELLOW), button -> {
            serverUrlField.setText("ws://race.flomik.xyz:8080");
        }));

        // Cancel button
        this.addDrawableChild(ButtonWidgetHelper.create(centerX + 55, centerY + 30, 100, 20, 
                ScreenTexts.CANCEL, button -> {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
        
        // Label
        context.drawCenteredTextWithShadow(this.textRenderer, 
                Text.literal("Enter server URL:").formatted(Formatting.GRAY), 
                this.width / 2, this.height / 2 - 25, 0xFFAAAAAA);

        // Render widgets
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
