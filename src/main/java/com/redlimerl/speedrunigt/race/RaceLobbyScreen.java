package com.redlimerl.speedrunigt.race;

import com.mojang.authlib.GameProfile;
import com.redlimerl.speedrunigt.utils.ButtonWidgetHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.session.Session;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ApiServices;
import net.minecraft.util.Colors;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class RaceLobbyScreen extends Screen {
	    private final Screen parent;

    private RaceState lastState = null;

    private TextFieldWidget roomCodeField;
    private TextFieldWidget serverUriField;

	    private ButtonWidget startButton;
	    private ButtonWidget readyButton;
	    private long codeCopiedUntil = 0;

	    private final ConcurrentHashMap<String, GameProfile> mojangProfileByNameKey = new ConcurrentHashMap<>();
	    private final Set<String> mojangProfileResolveInFlight = ConcurrentHashMap.newKeySet();
	    private final ConcurrentHashMap<String, Supplier<SkinTextures>> skinSupplierByNameKey = new ConcurrentHashMap<>();

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

        // Check server health in IDLE state
	        if (race.getState() == RaceState.IDLE) {
            race.checkServerHealth();
        }

        if (race.getState() == RaceState.IDLE) {
            // IDLE state: Show server field and room code input

            // Server CHANGE button (positioned in render method)
            this.serverUriField = new TextFieldWidget(this.textRenderer, centerX - 100, 0, 200, 20, Text.translatable("speedrunigt.race.server"));
            this.serverUriField.setText(race.getServerUri().toString());
            this.serverUriField.setVisible(false);
            this.addDrawableChild(this.serverUriField);

            // CHANGE button for server (will be positioned in SERVER box)
            int serverBoxY = 80;
            int boxWidth = 400;
            int boxX = centerX - boxWidth / 2;
            this.addDrawableChild(ButtonWidgetHelper.create(boxX + boxWidth - 70, serverBoxY + 46, 60, 18, 
                    Text.literal("CHANGE").formatted(Formatting.WHITE), button -> {
                // Show server change screen
                if (this.client != null) {
                    this.client.setScreen(new ServerChangeScreen(this, race));
                }
            }));

            // Room code input field - positioned INSIDE ROOM box
            int roomBoxY = serverBoxY + 70 + 15; // After SERVER box
            int inputY = roomBoxY + 48; // Inside ROOM box
            this.roomCodeField = new TextFieldWidget(this.textRenderer, centerX - 90, inputY, 120, 20, Text.translatable("speedrunigt.race.room_code"));
            this.roomCodeField.setMaxLength(6);
            this.roomCodeField.setPlaceholder(Text.literal("XXXXXX").formatted(Formatting.DARK_GRAY));
            this.addDrawableChild(this.roomCodeField);

            // JOIN button next to input - INSIDE ROOM box
            this.addDrawableChild(ButtonWidgetHelper.create(centerX - 90 + 125, inputY, 60, 20, 
                    Text.literal("JOIN").formatted(Formatting.WHITE), button -> {
                if (roomCodeField != null && !roomCodeField.getText().isEmpty()) {
                    race.joinRoom(roomCodeField.getText());
                }
            }));

            // CREATE NEW ROOM button (big button) - BELOW "or", inside ROOM box
            this.addDrawableChild(ButtonWidgetHelper.create(centerX - 100, inputY + 52, 200, 24, 
                    Text.literal("CREATE NEW ROOM").formatted(Formatting.WHITE), button -> {
                race.createRoom();
            }));

	        } else {
	            // IN LOBBY/GAME state: Show player list, ready button, etc.
	            int playersPanelWidth = Math.min(260, Math.max(200, this.width / 4));
	            int playersPanelX = this.width - playersPanelWidth;
	            int mainPanelWidth = Math.min(520, Math.max(320, playersPanelX - 80));
	            int mainPanelX = Math.max(10, (playersPanelX - mainPanelWidth) / 2);
	            int mainPanelY = 70;
	            int mainPanelHeight = this.height - 110;
	            int buttonWidth = Math.min(240, mainPanelWidth - 60);
	            int buttonX = mainPanelX + (mainPanelWidth - buttonWidth) / 2;

	            if (race.getState() == RaceState.LOBBY || race.getState() == RaceState.FINISHED) {
	                this.startButton = this.addDrawableChild(ButtonWidgetHelper.create(buttonX, mainPanelY + mainPanelHeight - 84, buttonWidth, 20, Text.translatable("speedrunigt.race.start"), button -> {
	                    race.requestStart();
	                    updateStartButton();
	                }));
	            } else {
	                this.startButton = null;
	            }

	            int readyButtonY = mainPanelY + mainPanelHeight - 58;
	            this.readyButton = this.addDrawableChild(ButtonWidgetHelper.create(buttonX, readyButtonY, buttonWidth, 20, Text.translatable("speedrunigt.race.ready"), button -> {
	                race.setReady(!race.isLocalReady());
	                updateReadyButton();
	                updateStartButton();
	            }));

	            this.addDrawableChild(ButtonWidgetHelper.create(buttonX, readyButtonY + 26, buttonWidth, 20, Text.translatable("speedrunigt.race.leave_room"), button -> {
	                race.leaveRoom();
	                this.init(this.width, this.height);
	            }));
	        }

        // Back button (bottom left)
        this.addDrawableChild(ButtonWidgetHelper.create(20, this.height - 30, 80, 20, 
                Text.literal("← Back"), button -> this.close()));
        
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
        RaceSessionManager race = RaceSessionManager.getInstance();
        boolean isStarting = race.getState() == RaceState.STARTING;
        boolean isLeader = race.isLocalPlayerLeader();
        
        // Lock button if starting and NOT leader
        if (isStarting && !isLeader) {
             readyButton.active = false;
             readyButton.setMessage(Text.translatable("speedrunigt.race.starting")); // Optional: change text
        } else {
             readyButton.active = true;
             readyButton.setMessage(race.isLocalReady()
                ? Text.translatable("speedrunigt.race.unready")
                : Text.translatable("speedrunigt.race.ready"));
        }
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
    public boolean mouseClicked(Click click, boolean doubled) {
        boolean consumed = super.mouseClicked(click, doubled);
        if (!consumed && click.button() == 1) { // Right click
            RaceSessionManager race = RaceSessionManager.getInstance();
            if (race.getState() != RaceState.IDLE && !race.getRoomCode().isEmpty()) {
                int playersPanelWidth = Math.min(260, Math.max(200, this.width / 4));
                int playersPanelX = this.width - playersPanelWidth;
                int mainPanelWidth = Math.min(520, Math.max(320, playersPanelX - 80));
                int mainPanelX = Math.max(10, (playersPanelX - mainPanelWidth) / 2);
                int mainPanelCenterX = mainPanelX + mainPanelWidth / 2;
                int mainPanelY = 70;

                String code = race.getRoomCode();
                int codeWidth = (int) (this.textRenderer.getWidth(code) * 2.2f);
                int codeHeight = (int) (this.textRenderer.fontHeight * 2.2f);
                int codeY = mainPanelY + 48;

                if (click.x() >= mainPanelCenterX - codeWidth / 2 - 5
                        && click.x() <= mainPanelCenterX + codeWidth / 2 + 5
                        && click.y() >= codeY - 2
                        && click.y() <= codeY + codeHeight + 2) {
                    if (this.client != null) {
                        this.client.keyboard.setClipboard(code);
                        codeCopiedUntil = System.currentTimeMillis() + 2000;
                    }
                    return true;
                }
            }
        }
        return consumed;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
	    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
	        RaceSessionManager race = RaceSessionManager.getInstance();
	        int centerX = this.width / 2;

	        // Title Image
	        Identifier titleTexture = Identifier.of("speedrunigt", "textures/ui/title.png");
	        int drawWidth = 204; 
	        int drawHeight = 36;
	        context.drawTexture(RenderPipelines.GUI_TEXTURED, titleTexture, centerX - (drawWidth / 2), 10, 0, 0, drawWidth, drawHeight, drawWidth, drawHeight);

        // Subtitle: "Find items. Beat others. Win."
        context.drawCenteredTextWithShadow(this.textRenderer, 
                Text.literal("Find items. Beat others. Win.").formatted(Formatting.GRAY), 
                centerX, 50, 0xFFAAAAAA);

	        // Connection status (bottom right)
	        String statusText = "Connected to: " + race.getServerUri().toString().replace("ws://", "");
	        int statusY = this.height - this.textRenderer.fontHeight - 4;
	        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusText).formatted(Formatting.GRAY), centerX, statusY, 0xFFAAAAAA);

        if (race.getLastError() != null && !race.getLastError().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, race.getLastError(), centerX, this.height - 44, 0xFFFF5555);
        }

	        if (race.getState() == RaceState.IDLE) {
            // IDLE STATE: Draw SERVER and ROOM boxes FIRST (before widgets)
            int boxWidth = 400;
            int boxX = centerX - boxWidth / 2;
            int currentY = 80;

            // ===== SERVER BOX =====
            int serverBoxHeight = 70;
            drawBox(context, boxX, currentY, boxWidth, serverBoxHeight);
            
            // SERVER title
            context.drawCenteredTextWithShadow(this.textRenderer, 
                    Text.literal("SERVER").formatted(Formatting.GOLD, Formatting.BOLD), 
                    centerX, currentY + 8, 0xFFFFAA00);
            
            // Server info
            int serverInfoY = currentY + 25;
            
            // Check if this is the default/official server
            String serverUrl = race.getServerUri().toString().replace("ws://", "");
            boolean isOfficialServer = serverUrl.equals("race.flomik.xyz:8080");
            
            // Server name with icon
            if (isOfficialServer) {
                context.drawTextWithShadow(this.textRenderer, 
                        Text.literal("Official").formatted(Formatting.BOLD).append(Text.literal(" ItemRace Server")).formatted(Formatting.WHITE),
                        boxX + 15, serverInfoY, 0xFFFFFFFF);
            } else {
                context.drawTextWithShadow(this.textRenderer, 
                        Text.literal("Community Server").formatted(Formatting.WHITE),
                        boxX + 15, serverInfoY, 0xFFFFFFFF);
            }
            
            // Status - show "Online" only when CONNECTED
            if (race.getConnectionStatus() == RaceSessionManager.ConnectionStatus.CONNECTED) {
                context.drawTextWithShadow(this.textRenderer, 
                        Text.literal("Online").formatted(Formatting.GREEN), 
                        boxX + boxWidth - 65, serverInfoY, 0xFF55FF55);
            }
            
            // Server URL
            context.drawTextWithShadow(this.textRenderer, 
                    Text.literal(serverUrl).formatted(Formatting.GRAY), 
                    boxX + 35, serverInfoY + 12, 0xFFAAAAAA);

            currentY += serverBoxHeight + 15;

            // ===== ROOM BOX =====
            int roomBoxHeight = 140; // Increased to fit all elements
            drawBox(context, boxX, currentY, boxWidth, roomBoxHeight);
            
            // ROOM title
            context.drawCenteredTextWithShadow(this.textRenderer, 
                    Text.literal("ROOM").formatted(Formatting.GOLD, Formatting.BOLD), 
                    centerX, currentY + 8, 0xFFFFAA00);
            
            // "Enter code to join an existing room"
            context.drawCenteredTextWithShadow(this.textRenderer, 
                    Text.literal("Enter code to join an existing room").formatted(Formatting.GRAY), 
                    centerX, currentY + 28, 0xFFCCCCCC);

            // "or" text (between input and CREATE button) - moved up
            context.drawCenteredTextWithShadow(this.textRenderer, 
                    Text.literal("or").formatted(Formatting.GRAY), 
                    centerX, currentY + 76, 0xFFAAAAAA);

            // NOW render widgets on top - they will be drawn by the code below
	        } else {
	            int playersPanelWidth = Math.min(260, Math.max(200, this.width / 4));
	            int playersPanelX = this.width - playersPanelWidth;
	            int playersPanelHeight = this.height;
	            drawBox(context, playersPanelX, 0, playersPanelWidth, playersPanelHeight);
	            renderPlayersPanel(context, playersPanelX, 0, playersPanelWidth, playersPanelHeight, race.getPlayers());

	            int mainPanelWidth = Math.min(520, Math.max(320, playersPanelX - 80));
	            int mainPanelX = Math.max(10, (playersPanelX - mainPanelWidth) / 2);
	            int mainPanelY = 70;
	            int mainPanelHeight = this.height - 110;
	            int mainPanelCenterX = mainPanelX + mainPanelWidth / 2;
	            drawBox(context, mainPanelX, mainPanelY, mainPanelWidth, mainPanelHeight);

	            Text header = race.getTargetItemId().isEmpty()
	                    ? Text.literal("WAITING FOR ITEM LIST...").formatted(Formatting.GOLD, Formatting.BOLD)
	                    : Text.literal("TARGET").formatted(Formatting.GOLD, Formatting.BOLD);
	            context.drawCenteredTextWithShadow(this.textRenderer, header, mainPanelCenterX, mainPanelY + 12, 0xFFFFAA00);

	            context.drawCenteredTextWithShadow(this.textRenderer,
	                    Text.literal("ROOM CODE:").formatted(Formatting.GRAY, Formatting.BOLD),
	                    mainPanelCenterX, mainPanelY + 34, 0xFFCCCCCC);

	            drawScaledCenteredText(context,
	                    Text.literal(race.getRoomCode()).formatted(Formatting.YELLOW, Formatting.BOLD),
	                    mainPanelCenterX, mainPanelY + 48, 2.2f, 0xFFFFFF55);

	            // "RMB to copy" hint or "Copied!" feedback
	            boolean showCopied = System.currentTimeMillis() < codeCopiedUntil;
	            Text copyHint = showCopied
	                    ? Text.literal("Copied!").formatted(Formatting.GREEN)
	                    : Text.literal("RMB on code to copy").formatted(Formatting.DARK_GRAY);
	            context.drawCenteredTextWithShadow(this.textRenderer, copyHint, mainPanelCenterX, mainPanelY + 72, showCopied ? 0xFF55FF55 : 0xFF666666);

	            if (race.getTargetItemId().isPresent()) {
	                renderTargetSection(context, mainPanelCenterX, mainPanelY + 92);
	            }

	            if (race.getState() == RaceState.STARTING) {
	                renderCountdown(context, mainPanelCenterX, mainPanelY + mainPanelHeight / 2, race.getCountdownSecondsRemaining());
	            }
	        }

	        // Render all widgets (buttons, text fields) on TOP of boxes
	        super.render(context, mouseX, mouseY, delta);
	    }

    private void drawBox(DrawContext context, int x, int y, int width, int height) {
        // Dark semi-transparent background
        context.fill(x, y, x + width, y + height, 0xAA000000);
        
        // Border (light gray)
        int borderColor = 0xFF555555;
        context.fill(x, y, x + width, y + 1, borderColor); // Top
        context.fill(x, y + height - 1, x + width, y + height, borderColor); // Bottom
        context.fill(x, y, x + 1, y + height, borderColor); // Left
        context.fill(x + width - 1, y, x + width, y + height, borderColor); // Right
    }

	    private void drawScaledCenteredText(DrawContext context, Text text, int centerX, int y, float scale, int color) {
	        context.getMatrices().pushMatrix();
	        context.getMatrices().scale(scale, scale);
	        context.drawCenteredTextWithShadow(this.textRenderer, text, (int) (centerX / scale), (int) (y / scale), color);
	        context.getMatrices().popMatrix();
	    }

    private void renderInputLabels(DrawContext context, int centerX) {
        if (serverUriField != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.server_address"), centerX, serverUriField.getY() - 12, 0xFFCCCCCC);
        }
        if (roomCodeField != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("speedrunigt.race.room_code_label"), centerX, roomCodeField.getY() - 12, 0xFFCCCCCC);
        }
    }

	    private void renderPlayersPanel(DrawContext context, int panelX, int panelY, int panelWidth, int panelHeight, List<RaceSessionManager.PlayerStatus> players) {
	        int innerX = panelX + 10;
	        int y = panelY + 10;

	        List<RaceSessionManager.PlayerStatus> sortedPlayers = getPlayersSortedLeaderFirst(players);
	        context.drawCenteredTextWithShadow(this.textRenderer,
	                Text.literal("PLAYERS (" + sortedPlayers.size() + ")").formatted(Formatting.GOLD, Formatting.BOLD),
	                panelX + panelWidth / 2, y, 0xFFFFAA00);
	        y += 16;

	        int rowHeight = 26;
	        int rowWidth = panelWidth - 20;
	        for (int i = 0; i < sortedPlayers.size(); i++) {
	            int rowY = y + i * rowHeight;
	            int rowBoxHeight = rowHeight - 4;
	            drawBox(context, innerX, rowY, rowWidth, rowBoxHeight);

	            RaceSessionManager.PlayerStatus p = sortedPlayers.get(i);
	                Identifier skin = getPlayerSkinTexture(p.name());
	                int headX = innerX + 2;
	                int headY = rowY + 2;
	                context.drawTexture(RenderPipelines.GUI_TEXTURED, skin, headX, headY, 8, 8, 16, 16, 8, 8, 64, 64);
	                context.drawTexture(RenderPipelines.GUI_TEXTURED, skin, headX, headY, 40, 8, 16, 16, 8, 8, 64, 64);

					if (p.isLeader()) {
	                    context.fill(headX, headY, headX + 4, headY + 4, 0xFFAA00FF);
	                }

	                int nameX = innerX + 26;
	                MutableText nameText = Text.literal(p.name()).formatted(Formatting.WHITE);
	                context.drawTextWithShadow(this.textRenderer, nameText, nameX, rowY + 6, Colors.WHITE);

	                String readyIcon = p.ready() ? "✔" : "✖";
	                int readyColor = p.ready() ? 0xFF55FF55 : 0xFFCCCCCC;
	                int readyX = innerX + rowWidth - 16;
	                context.drawTextWithShadow(this.textRenderer, readyIcon, readyX, rowY + 8, readyColor);
	        }
	    }

	    private static List<RaceSessionManager.PlayerStatus> getPlayersSortedLeaderFirst(List<RaceSessionManager.PlayerStatus> players) {
	        if (players == null || players.isEmpty()) return List.of();
	        int leaderIndex = -1;
	        for (int i = 0; i < players.size(); i++) {
	            if (players.get(i).isLeader()) {
	                leaderIndex = i;
	                break;
	            }
	        }
	        if (leaderIndex <= 0) return players;
	        java.util.ArrayList<RaceSessionManager.PlayerStatus> sorted = new java.util.ArrayList<>(players.size());
	        sorted.add(players.get(leaderIndex));
	        for (int i = 0; i < players.size(); i++) {
	            if (i == leaderIndex) continue;
	            sorted.add(players.get(i));
	        }
	        return sorted;
	    }

	    private Identifier getPlayerSkinTexture(String playerName) {
	        if (client == null) {
	            return Identifier.of("textures/entity/player/wide/alex.png");
	        }

	        String nameKey = normalizeNameKey(playerName);
	        Session session = client.getSession();
	        if (session != null && normalizeNameKey(session.getUsername()).equals(nameKey)) {
            UUID sessionUuid = session.getUuidOrNull();
            if (sessionUuid != null && client.getNetworkHandler() != null) {
                PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(sessionUuid);
                if (entry != null) return entry.getSkinTextures().body().texturePath();
            }

            Supplier<SkinTextures> localSupplier = client.getSkinProvider().supplySkinTextures(client.getGameProfile(), false);
	            return localSupplier.get().body().texturePath();
	        }

	        GameProfile profile = resolveSkinProfile(playerName, nameKey);
	        Supplier<SkinTextures> supplier = skinSupplierByNameKey.computeIfAbsent(nameKey, k -> client.getSkinProvider().supplySkinTextures(profile, false));
	        return supplier.get().body().texturePath();
	    }

	    private GameProfile resolveSkinProfile(String playerName, String nameKey) {
	        Session session = client.getSession();
	        if (session != null && normalizeNameKey(session.getUsername()).equals(nameKey)) {
	            UUID uuid = session.getUuidOrNull();
	            if (uuid != null) return client.getGameProfile();
	        }

	        GameProfile cached = mojangProfileByNameKey.get(nameKey);
	        if (cached != null) return cached;

	        startMojangProfileResolve(playerName, nameKey);
	        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
	        return new GameProfile(offline, playerName);
	    }

	    private void startMojangProfileResolve(String playerName, String nameKey) {
	        if (!mojangProfileResolveInFlight.add(nameKey)) return;
	        ApiServices apiServices = client.getApiServices();
	        if (apiServices == null) {
	            mojangProfileResolveInFlight.remove(nameKey);
	            return;
	        }

	        MinecraftClient clientRef = client;
	        CompletableFuture
	                .supplyAsync(
	                        () -> {
	                            GameProfile profile = apiServices.profileResolver().getProfileByName(playerName).orElse(null);
	                            if (profile == null) return null;

	                            try {
	                                var result = apiServices.sessionService().fetchProfile(profile.id(), false);
	                                if (result != null && result.profile() != null) return result.profile();
	                            } catch (Exception ignored) {}

	                            return profile;
	                        },
	                        Util.getMainWorkerExecutor()
	                )
	                .thenAccept(profile -> clientRef.execute(() -> {
	                    mojangProfileResolveInFlight.remove(nameKey);
	                    if (profile == null) return;
	                    mojangProfileByNameKey.put(nameKey, profile);
	                    skinSupplierByNameKey.remove(nameKey);
	                }))
	                .exceptionally(ex -> {
	                    clientRef.execute(() -> mojangProfileResolveInFlight.remove(nameKey));
	                    return null;
	                });
	    }

    private static String normalizeNameKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private void renderTargetSection(DrawContext context, int centerX, int y) {
        RaceSessionManager race = RaceSessionManager.getInstance();
        if (race.getTargetItemId().isEmpty()) return;

        ItemStack stack = new ItemStack(Registries.ITEM.get(race.getTargetItemId().get()));
        float itemScale = 3.0f;
        int itemSize = (int) (16 * itemScale);
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(itemScale, itemScale);
        context.drawItem(stack, (int) (centerX / itemScale) - 8, (int) (y / itemScale));
        context.getMatrices().popMatrix();
        context.drawCenteredTextWithShadow(this.textRenderer, stack.getName(), centerX, y + itemSize + 4, Colors.WHITE);
    }

    private void renderCountdown(DrawContext context, int centerX, int centerY, int secondsRemaining) {
        if (secondsRemaining <= 0) return;
        Text text = Text.translatable("speedrunigt.race.countdown", secondsRemaining);
        float scale = 3.5f;

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scale, scale);
        context.drawCenteredTextWithShadow(this.textRenderer, text, (int) (centerX / scale), (int) (centerY / scale), Colors.WHITE);
        context.getMatrices().popMatrix();
    }
}
