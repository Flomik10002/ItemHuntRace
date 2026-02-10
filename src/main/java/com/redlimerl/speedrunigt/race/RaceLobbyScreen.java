package com.redlimerl.speedrunigt.race;

import com.mojang.authlib.GameProfile;
import com.redlimerl.speedrunigt.utils.ButtonWidgetHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.session.Session;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
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
    private static final int ROOM_CODE_Y = 80;
    private static final int COPY_CODE_BUTTON_WIDTH = 90;
    private static final int COPY_CODE_BUTTON_HEIGHT = 20;
    private static final int COPY_CODE_BUTTON_MARGIN_X = 6;
    private final Screen parent;

    private RaceState lastState = null;

    private TextFieldWidget roomCodeField;
    private TextFieldWidget serverUriField;

    private ButtonWidget startButton;
    private ButtonWidget readyButton;
    private ButtonWidget copyCodeButton;

    private final ConcurrentHashMap<String, UUID> mojangUuidByNameKey = new ConcurrentHashMap<>();
    private final Set<String> mojangUuidResolveInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Supplier<SkinTextures>> skinSupplierByNameKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Supplier<SkinTextures>> skinSupplierByUuidKey = new ConcurrentHashMap<>();

    public RaceLobbyScreen(Screen parent) {
        super(Text.translatable("speedrunigt.race.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearChildren();
        this.copyCodeButton = null;

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
        int statusX = Math.max(4, this.width - this.textRenderer.getWidth(statusText) - 4);
        int statusY = this.height - this.textRenderer.fontHeight - 4;
        context.drawTextWithShadow(this.textRenderer, statusText, statusX, statusY, 0xFFAAAAAA);

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
            // IN-LOBBY STATE: Show room code, player list, etc.
            Text roomCodeText = Text.translatable("speedrunigt.race.room_code.label", race.getRoomCode());
            context.drawCenteredTextWithShadow(this.textRenderer, roomCodeText, centerX, ROOM_CODE_Y, Colors.WHITE);

            int infoTop = ROOM_CODE_Y + 36;
            renderPlayerListTopLeft(context, 20, infoTop, race.getPlayers());
            renderTargetSection(context, centerX, infoTop);

            if (race.getState() == RaceState.STARTING) {
                renderCountdown(context, centerX, this.height / 2, race.getCountdownSecondsRemaining());
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

    private void updateCopyCodeButtonPosition(int centerX, Text roomCodeText) {
        if (copyCodeButton == null) return;

        int textWidth = this.textRenderer.getWidth(roomCodeText);
        int buttonX = centerX + (textWidth / 2) + COPY_CODE_BUTTON_MARGIN_X;
        int buttonY = ROOM_CODE_Y + (this.textRenderer.fontHeight - COPY_CODE_BUTTON_HEIGHT) / 2;

        if (buttonX + COPY_CODE_BUTTON_WIDTH > this.width - 4) {
            buttonX = centerX - COPY_CODE_BUTTON_WIDTH / 2;
            buttonY = ROOM_CODE_Y + this.textRenderer.fontHeight + 4;
        }

        copyCodeButton.setDimensionsAndPosition(COPY_CODE_BUTTON_WIDTH, COPY_CODE_BUTTON_HEIGHT, buttonX, buttonY);
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
            // Use name-based resolution because server IDs are random
            Identifier skin = getPlayerSkinTexture(p.name());
            
            // Draw head (8,8 to 16,16)
            context.drawTexture(RenderPipelines.GUI_TEXTURED, skin, x, y, 8, 8, 8, 8, 64, 64);
            // Draw overlay (40,8 to 48,16)
            context.drawTexture(RenderPipelines.GUI_TEXTURED, skin, x, y, 40, 8, 8, 8, 64, 64);
            
            int textX = x + 12;
            MutableText nameText = Text.literal(p.name());
            
            if (p.isLeader()) {
                nameText = Text.literal("LEADER ").formatted(Formatting.GOLD, Formatting.BOLD).append(nameText.formatted(Formatting.YELLOW));
            } else {
                nameText = nameText.formatted(Formatting.WHITE);
            }
            
            context.drawTextWithShadow(this.textRenderer, nameText, textX, y, Colors.WHITE);
            
            // Readiness Indicator (Checkmark or Cross)
            String readyIcon = p.ready() ? "✔" : "✖";
            int readyColor = p.ready() ? 0xFF55FF55 : 0xFFCCCCCC; // Green or Gray
            int readyX = x + 120; // Fixed width for name column
            context.drawTextWithShadow(this.textRenderer, readyIcon, readyX, y, readyColor);

            y += 12;
        }
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

        UUID skinUuid = resolveSkinUuid(playerName, nameKey);
        Supplier<SkinTextures> supplier = skinSupplierByNameKey.computeIfAbsent(nameKey, k ->
                client.getSkinProvider().supplySkinTextures(new GameProfile(skinUuid, playerName), false)
        );
        return supplier.get().body().texturePath();
    }

    private Identifier getPlayerSkinTextureByUuid(UUID playerUuid, String playerName) {
        if (client == null) {
            return Identifier.of("textures/entity/player/wide/alex.png");
        }

        // Check if this is the local player
        Session session = client.getSession();
        UUID localUuid = session != null ? session.getUuidOrNull() : null;
        if (localUuid != null && localUuid.equals(playerUuid)) {
            // Use local player's skin
            if (client.getNetworkHandler() != null) {
                PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(localUuid);
                if (entry != null) return entry.getSkinTextures().body().texturePath();
            }
            Supplier<SkinTextures> localSupplier = client.getSkinProvider().supplySkinTextures(client.getGameProfile(), false);
            return localSupplier.get().body().texturePath();
        }

        // Use server-provided UUID to fetch skin
        String uuidKey = playerUuid.toString();
        Supplier<SkinTextures> supplier = skinSupplierByUuidKey.computeIfAbsent(uuidKey, k ->
                client.getSkinProvider().supplySkinTextures(new GameProfile(playerUuid, playerName), false)
        );
        return supplier.get().body().texturePath();
    }

    private UUID resolveSkinUuid(String playerName, String nameKey) {
        Session session = client.getSession();
        if (session != null && normalizeNameKey(session.getUsername()).equals(nameKey)) {
            UUID uuid = session.getUuidOrNull();
            if (uuid != null) return uuid;
        }

        UUID cached = mojangUuidByNameKey.get(nameKey);
        if (cached != null) return cached;

        startMojangUuidResolve(playerName, nameKey);
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    private void startMojangUuidResolve(String playerName, String nameKey) {
        if (!mojangUuidResolveInFlight.add(nameKey)) return;
        ApiServices apiServices = client.getApiServices();
        if (apiServices == null) {
            mojangUuidResolveInFlight.remove(nameKey);
            return;
        }

        MinecraftClient clientRef = client;
        CompletableFuture
                .supplyAsync(
                        () -> apiServices.profileResolver().getProfileByName(playerName).map(GameProfile::id).orElse(null),
                        Util.getMainWorkerExecutor()
                )
                .thenAccept(uuid -> {
                    if (uuid == null) {
                        clientRef.execute(() -> mojangUuidResolveInFlight.remove(nameKey));
                        return;
                    }

                    clientRef.execute(() -> {
                        mojangUuidByNameKey.put(nameKey, uuid);
                        skinSupplierByNameKey.remove(nameKey);
                    });
                })
                .exceptionally(ex -> {
                    clientRef.execute(() -> mojangUuidResolveInFlight.remove(nameKey));
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
