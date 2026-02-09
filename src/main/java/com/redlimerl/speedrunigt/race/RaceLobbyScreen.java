package com.redlimerl.speedrunigt.race;

import com.redlimerl.speedrunigt.utils.ButtonWidgetHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.net.URI;
import java.util.List;

@Environment(EnvType.CLIENT)
public class RaceLobbyScreen extends Screen {
    private final Screen parent;

    private RaceState lastState = null;

    private TextFieldWidget roomCodeField;
    private TextFieldWidget serverUriField;

    private ButtonWidget startButton;
    private ButtonWidget readyButton;
    private ButtonWidget copyCodeButton;

    public RaceLobbyScreen(Screen parent) {
        super(Text.translatable("speedrunigt.race.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearChildren();

        RaceSessionManager race = RaceSessionManager.getInstance();
        lastState = race.getState();

        int centerX = this.width / 2;
        int y = 78;

        if (race.getState() == RaceState.IDLE) {
            this.serverUriField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20, Text.translatable("speedrunigt.race.server"));
            this.serverUriField.setText(race.getServerUri().toString());
            this.addDrawableChild(this.serverUriField);

            y += 64;

            this.roomCodeField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20, Text.translatable("speedrunigt.race.room_code"));
            this.roomCodeField.setMaxLength(12);
            this.addDrawableChild(this.roomCodeField);

            y += 56;

            this.addDrawableChild(ButtonWidgetHelper.create(centerX - 100, y, 98, 20, Text.translatable("speedrunigt.race.create_room"), button -> {
                applyServerUri();
                race.createRoom();
            }));
            this.addDrawableChild(ButtonWidgetHelper.create(centerX + 2, y, 98, 20, Text.translatable("speedrunigt.race.join_room"), button -> {
                applyServerUri();
                if (roomCodeField != null) race.joinRoom(roomCodeField.getText());
            }));
        } else {

            if (race.getState() == RaceState.LOBBY || race.getState() == RaceState.FINISHED) {
                this.startButton = this.addDrawableChild(ButtonWidgetHelper.create(centerX - 100, this.height - 108, 200, 20, Text.translatable("speedrunigt.race.start"), button -> {
                    race.requestStart();
                    updateStartButton();
                }));
            } else {
                this.startButton = null;
            }

            int buttonY = this.height - 84;
            this.readyButton = this.addDrawableChild(ButtonWidgetHelper.create(centerX - 100, buttonY, 200, 20, Text.translatable("speedrunigt.race.ready"), button -> {
                race.setReady(!race.isLocalReady());
                updateReadyButton();
                updateStartButton();
            }));

            this.addDrawableChild(ButtonWidgetHelper.create(centerX - 100, this.height - 60, 200, 20, Text.translatable("speedrunigt.race.leave_room"), button -> {
                race.leaveRoom();
                this.init(this.width, this.height);
            }));
        }

        this.addDrawableChild(ButtonWidgetHelper.create(centerX - 100, this.height - 28, 200, 20, ScreenTexts.BACK, button -> this.close()));
        updateReadyButton();
        updateStartButton();
    }

    private void applyServerUri() {
        if (serverUriField == null) return;
        String raw = serverUriField.getText().trim();
        if (raw.isEmpty()) return;
        try {
            URI uri = URI.create(raw);
            RaceSessionManager.getInstance().setServerUri(uri);
        } catch (Exception ignored) {}
    }

    private void updateReadyButton() {
        if (readyButton == null) return;
        readyButton.setMessage(RaceSessionManager.getInstance().isLocalReady()
                ? Text.translatable("speedrunigt.race.unready")
                : Text.translatable("speedrunigt.race.ready"));
    }

    private void updateStartButton() {
        if (startButton == null) return;
        RaceSessionManager race = RaceSessionManager.getInstance();
        boolean inWorld = this.client != null && this.client.world != null;
        boolean canStart = !inWorld &&
                (race.getState() == RaceState.LOBBY || race.getState() == RaceState.FINISHED) &&
                race.areAllPlayersReady();
        startButton.active = canStart;
        if (canStart) {
            startButton.setMessage(Text.translatable("speedrunigt.race.start"));
        } else if (inWorld) {
            startButton.setMessage(Text.translatable("speedrunigt.race.start_leave_world"));
        } else {
            startButton.setMessage(Text.translatable("speedrunigt.race.start_waiting"));
        }
    }

    @Override
    public void tick() {
        RaceState state = RaceSessionManager.getInstance().getState();
        if (state != lastState) {
            this.init(this.width, this.height);
            lastState = state;
        }
        updateReadyButton();
        updateStartButton();
    }

    @Override
    public void close() {
        if (this.client == null) return;
        if (this.client.world != null) {
            this.client.setScreen(null);
        } else {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        RaceSessionManager race = RaceSessionManager.getInstance();
        int centerX = this.width / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 14, Colors.WHITE);

        String statusText = "Server: " + race.getServerUri() + " (" + race.getConnectionStatus().name() + ")";
        context.drawCenteredTextWithShadow(this.textRenderer, statusText, centerX, 28, 0xFFAAAAAA);

        if (race.getLastError() != null && !race.getLastError().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, race.getLastError(), centerX, this.height - 44, 0xFFFF5555);
        }

        if (race.getState() == RaceState.IDLE) {
            renderInputLabels(context, centerX);
            return;
        }

        int y = 40;
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.room_code.label", race.getRoomCode()), centerX, y, Colors.WHITE);

        int infoTop = 86;
        renderPlayerListTopLeft(context, 20, infoTop, race.getPlayers());
        renderTargetSection(context, centerX, infoTop);

        if (race.getState() == RaceState.STARTING) {
            renderCountdown(context, centerX, this.height / 2, race.getCountdownSecondsRemaining());
        }
    }

    private void renderInputLabels(DrawContext context, int centerX) {
        if (serverUriField != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.server_address"), centerX, serverUriField.getY() - 12, 0xFFCCCCCC);
        }
        if (roomCodeField != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.room_code_label"), centerX, roomCodeField.getY() - 12, 0xFFCCCCCC);
        }
    }

    private void renderPlayerListTopLeft(DrawContext context, int x, int startY, List<RaceSessionManager.PlayerStatus> players) {
        int y = startY;
        context.drawTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.players"), x, y, Colors.WHITE);
        y += 14;
        for (RaceSessionManager.PlayerStatus p : players) {
            String line = (p.ready() ? "[READY] " : "[.....] ") + p.name();
            context.drawTextWithShadow(this.textRenderer, line, x, y, p.ready() ? 0xFF55FF55 : 0xFFCCCCCC);
            y += 12;
        }
    }

    private void renderTargetSection(DrawContext context, int centerX, int y) {
        RaceSessionManager race = RaceSessionManager.getInstance();
        if (race.getTargetItemId().isEmpty()) return;

        ItemStack stack = new ItemStack(Registries.ITEM.get(race.getTargetItemId().get()));
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.target"), centerX, y, Colors.WHITE);
        context.drawItem(stack, centerX - 8, y + 14);
        context.drawCenteredTextWithShadow(this.textRenderer, stack.getName(), centerX, y + 38, Colors.WHITE);
    }

    private void renderCountdown(DrawContext context, int centerX, int centerY, int secondsRemaining) {
        if (secondsRemaining <= 0) return;
        Text text = Text.translatable("speedrunigt.race.countdown", secondsRemaining);
        float scale = 2.0f;

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scale, scale);
        context.drawCenteredTextWithShadow(this.textRenderer, text, (int) (centerX / scale), (int) (centerY / scale), Colors.WHITE);
        context.getMatrices().popMatrix();
    }
}
