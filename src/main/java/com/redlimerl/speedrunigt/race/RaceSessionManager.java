package com.redlimerl.speedrunigt.race;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.redlimerl.speedrunigt.SpeedRunIGT;
import com.redlimerl.speedrunigt.timer.InGameTimer;
import com.redlimerl.speedrunigt.timer.InGameTimerUtils;
import com.redlimerl.speedrunigt.timer.TimerStatus;
import com.redlimerl.speedrunigt.timer.category.RunCategories;
import com.redlimerl.speedrunigt.timer.running.RunType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.registry.RegistryKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public final class RaceSessionManager {
    public enum ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }
    public enum FinishReason { TARGET_OBTAINED, DEATH }

    public record PlayerStatus(UUID id, String name, boolean ready) {}

    public static final String SERVER_URI_PROPERTY = "speedrunigt.race.server";

    private static final RaceSessionManager INSTANCE = new RaceSessionManager();
    public static RaceSessionManager getInstance() {
        return INSTANCE;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
    private volatile String lastError = null;

    private URI serverUri = URI.create(System.getProperty(SERVER_URI_PROPERTY, "ws://flomik.xyz:8080"));
    private WebSocket webSocket = null;
    private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final StringBuilder partialMessage = new StringBuilder();

    private RaceState state = RaceState.IDLE;
    private String roomCode = "";
    private boolean localReady = false;
    private final List<PlayerStatus> players = new ArrayList<>();
    private final ConcurrentHashMap<String, FinishTime> finishTimesByPlayerName = new ConcurrentHashMap<>();

    private String seedString = null;
    private Identifier targetItemId = null;
    private String pendingWorldDirectoryName = null;

    private long startScheduledAt = 0L;
    private boolean worldCreationRequested = false;
    private boolean timerConfigured = false;
    private final AtomicBoolean finishTriggered = new AtomicBoolean(false);

    private RaceSessionManager() {}

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public RaceState getState() {
        return state;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public boolean isLocalReady() {
        return localReady;
    }

    public List<PlayerStatus> getPlayers() {
        return List.copyOf(players);
    }

    public boolean areAllPlayersReady() {
        if (players.isEmpty()) return false;
        for (PlayerStatus player : players) {
            if (!player.ready()) return false;
        }
        return true;
    }

    public Optional<Identifier> getTargetItemId() {
        return Optional.ofNullable(targetItemId);
    }

    public int getCountdownSecondsRemaining() {
        if (state != RaceState.STARTING) return 0;
        long remainingMs = startScheduledAt - System.currentTimeMillis();
        if (remainingMs <= 0) return 0;
        return (int) Math.ceil(remainingMs / 1000.0);
    }

    public boolean isRaceControlsLocked() {
        return state != RaceState.IDLE;
    }

    public boolean shouldRenderTargetHud() {
        return state == RaceState.RUNNING && targetItemId != null;
    }

    public URI getServerUri() {
        return serverUri;
    }

    public void setServerUri(URI serverUri) {
        this.serverUri = Objects.requireNonNull(serverUri, "serverUri");
    }

    public void tick(MinecraftClient client) {
        if (state == RaceState.STARTING && !worldCreationRequested && System.currentTimeMillis() >= startScheduledAt) {
            worldCreationRequested = true;
            startWorld(client);
        }

        if (state == RaceState.STARTING && client.world != null && client.player != null) {
            state = RaceState.RUNNING;
            configureTimerForRace();
        }
    }

    public void connect() {
        if (connectionStatus == ConnectionStatus.CONNECTED || connectionStatus == ConnectionStatus.CONNECTING) return;

        lastError = null;
        connectionStatus = ConnectionStatus.CONNECTING;
        try {
            httpClient.newWebSocketBuilder()
                    .buildAsync(serverUri, new WsListener())
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            MinecraftClient.getInstance().execute(() -> onConnectionFailed(err));
                        } else {
                            MinecraftClient.getInstance().execute(() -> onConnected(ws));
                        }
                    });
        } catch (Exception e) {
            onConnectionFailed(e);
        }
    }

    public void disconnect() {
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
        this.pendingMessages.clear();
        this.partialMessage.setLength(0);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
            } catch (Exception ignored) {}
        }
    }

    public void createRoom() {
        if (state != RaceState.IDLE) return;
        connect();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "create_room");
        msg.addProperty("playerName", getClientPlayerName());
        send(msg);
    }

    public void joinRoom(String code) {
        if (state != RaceState.IDLE) return;
        String normalized = normalizeRoomCode(code);
        if (normalized.isEmpty()) return;

        connect();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "join_room");
        msg.addProperty("roomCode", normalized);
        msg.addProperty("playerName", getClientPlayerName());
        send(msg);
    }

    public void leaveRoom() {
        if (state == RaceState.IDLE) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "leave_room");
        msg.addProperty("roomCode", roomCode);
        send(msg);

        resetToIdle();
    }

    public void setReady(boolean ready) {
        if (state != RaceState.LOBBY && state != RaceState.STARTING && state != RaceState.FINISHED) return;
        localReady = ready;
        applyLocalReadyToPlayers();

        JsonObject readyMsg = new JsonObject();
        readyMsg.addProperty("type", "ready");
        readyMsg.addProperty("roomCode", roomCode);
        readyMsg.addProperty("ready", ready);
        readyMsg.addProperty("playerName", getClientPlayerName());
        send(readyMsg);

        if (!ready) {
            sendCancelStart();
        }
    }

    public void requestStart() {
        if (state != RaceState.LOBBY && state != RaceState.FINISHED) return;
        if (!areAllPlayersReady()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "start_request");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        send(msg);
    }

    private void sendCancelStart() {
        if (roomCode == null || roomCode.isEmpty()) return;
        sendCancelStartType("cancel_start");
        sendCancelStartType("start_cancel");
        sendCancelStartType("cancel_start_request");
    }

    private void sendCancelStartType(String type) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        send(msg);
    }

    private void cancelStarting() {
        if (state != RaceState.STARTING) return;
        state = RaceState.LOBBY;
        startScheduledAt = 0L;
        worldCreationRequested = false;
        timerConfigured = false;
        finishTriggered.set(false);
        seedString = null;
        targetItemId = null;
        pendingWorldDirectoryName = null;
    }

    private void applyLocalReadyToPlayers() {
        if (players.isEmpty()) return;
        String local = normalizePlayerKey(getClientPlayerName());
        for (int i = 0; i < players.size(); i++) {
            PlayerStatus p = players.get(i);
            if (normalizePlayerKey(p.name()).equals(local)) {
                if (p.ready() != localReady) {
                    players.set(i, new PlayerStatus(p.id(), p.name(), localReady));
                }
                return;
            }
        }
    }

    private void syncLocalReadyFromPlayers() {
        if (players.isEmpty()) return;
        String local = normalizePlayerKey(getClientPlayerName());
        for (PlayerStatus p : players) {
            if (normalizePlayerKey(p.name()).equals(local)) {
                localReady = p.ready();
                return;
            }
        }
    }

    public void finishRun(FinishReason reason) {
        if (state != RaceState.RUNNING) return;
        if (!finishTriggered.compareAndSet(false, true)) return;

        state = RaceState.FINISHED;
        resetReadyAfterRace();

        InGameTimer.complete();
        InGameTimer timer = InGameTimer.getInstance();

        finishTimesByPlayerName.put(normalizePlayerKey(getClientPlayerName()), new FinishTime(timer.getInGameTime(false), timer.getRealTimeAttack()));

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "finish");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("reason", reason.name().toLowerCase(Locale.ROOT));
        msg.addProperty("rtaMs", timer.getRealTimeAttack());
        msg.addProperty("igtMs", timer.getInGameTime(false));
        if (targetItemId != null) msg.addProperty("targetItemId", targetItemId.toString());
        send(msg);
    }

    private void resetToIdle() {
        state = RaceState.IDLE;
        roomCode = "";
        localReady = false;
        players.clear();
        finishTimesByPlayerName.clear();
        seedString = null;
        targetItemId = null;
        pendingWorldDirectoryName = null;
        startScheduledAt = 0L;
        worldCreationRequested = false;
        timerConfigured = false;
        finishTriggered.set(false);
    }

    private void resetReadyAfterRace() {
        localReady = false;
        if (state == RaceState.IDLE || roomCode.isEmpty()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "ready");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("ready", false);
        msg.addProperty("playerName", getClientPlayerName());
        send(msg);
    }

    private void startWorld(MinecraftClient client) {
        if (seedString == null || targetItemId == null) {
            lastError = "Missing START parameters";
            cancelStarting();
            return;
        }

        if (client.world != null) {
            lastError = "You must be in the main menu to start a race world";
            cancelStarting();
            return;
        }

        OptionalLong parsedSeed = GeneratorOptions.parseSeed(seedString);
        long seed = parsedSeed.isPresent() ? parsedSeed.getAsLong() : (long) seedString.hashCode();

        InGameTimerUtils.IS_SET_SEED = true;

        String directoryName = makeWorldDirectoryName();
        pendingWorldDirectoryName = directoryName;
        LevelInfo levelInfo = new LevelInfo(
                "Item Hunt Race",
                GameMode.SURVIVAL,
                true,
                Difficulty.HARD,
                false,
                new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES),
                new DataConfiguration(DataPackSettings.SAFE_MODE, FeatureFlags.DEFAULT_ENABLED_FEATURES)
        );

        GeneratorOptions generatorOptions = new GeneratorOptions(seed, true, false);

        IntegratedServerLoader loader = client.createIntegratedServerLoader();
        Screen parent = client.currentScreen;
        loader.createAndStart(
                directoryName,
                levelInfo,
                generatorOptions,
                wrapperLookup -> {
                    WorldPreset preset = wrapperLookup.getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.DEFAULT).value();
                    return preset.createDimensionsRegistryHolder();
                },
                parent
        );
    }

    private String makeWorldDirectoryName() {
        String code = roomCode == null || roomCode.isEmpty() ? "race" : roomCode.toLowerCase(Locale.ROOT);
        return "item_hunt_" + code + "_" + (System.currentTimeMillis() / 1000L);
    }

    private void send(JsonObject jsonObject) {
        String payload = SpeedRunIGT.GSON.toJson(jsonObject);
        WebSocket ws = webSocket;
        if (connectionStatus != ConnectionStatus.CONNECTED || ws == null) {
            pendingMessages.add(payload);
            connect();
            return;
        }
        ws.sendText(payload, true);
    }

    private void flushPending() {
        WebSocket ws = webSocket;
        if (connectionStatus != ConnectionStatus.CONNECTED || ws == null) return;
        String payload;
        while ((payload = pendingMessages.poll()) != null) {
            ws.sendText(payload, true);
        }
    }

    private void onConnected(WebSocket ws) {
        this.webSocket = ws;
        this.connectionStatus = ConnectionStatus.CONNECTED;
        this.lastError = null;
        flushPending();
    }

    private void onConnectionFailed(Throwable err) {
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
        this.webSocket = null;
        this.lastError = err.getMessage();
    }

    private void handleIncoming(String rawMessage) {
        JsonObject msg;
        try {
            JsonElement element = JsonParser.parseString(rawMessage);
            if (!element.isJsonObject()) return;
            msg = element.getAsJsonObject();
        } catch (JsonParseException ignored) {
            return;
        }

        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "room_created", "room_joined" -> {
                String code = msg.has("roomCode") ? msg.get("roomCode").getAsString() : "";
                if (code.isEmpty()) return;
                roomCode = normalizeRoomCode(code);
                localReady = false;
                players.clear();
                finishTimesByPlayerName.clear();
                if (msg.has("players") && msg.get("players").isJsonArray()) {
                    parsePlayers(msg.getAsJsonArray("players"));
                }
                syncLocalReadyFromPlayers();
                state = RaceState.LOBBY;
                finishTriggered.set(false);
                lastError = null;
            }
            case "room_update" -> {
                if (msg.has("players") && msg.get("players").isJsonArray()) {
                    players.clear();
                    parsePlayers(msg.getAsJsonArray("players"));
                    syncLocalReadyFromPlayers();
                }
            }
            case "finish" -> {
                String player = getStringFromKeys(msg, "playerName", "player", "name");
                Long igt = getLongFromKeys(msg, "igtMs", "igt", "igt_ms", "igtMillis", "igt_millis");
                Long rta = getLongFromKeys(msg, "rtaMs", "rta", "rta_ms", "rtaMillis", "rta_millis");
                if ((igt == null || rta == null) && msg.has("time") && msg.get("time").isJsonObject()) {
                    JsonObject time = msg.getAsJsonObject("time");
                    if (igt == null) igt = getLongFromKeys(time, "igtMs", "igt", "igt_ms", "igtMillis", "igt_millis");
                    if (rta == null) rta = getLongFromKeys(time, "rtaMs", "rta", "rta_ms", "rtaMillis", "rta_millis");
                }
                if (player != null && igt != null && rta != null) {
                    finishTimesByPlayerName.put(normalizePlayerKey(player), new FinishTime(igt, rta));
                }
            }
            case "winner" -> {
                String winner = getStringFromKeys(msg, "player", "playerName", "name");
                if (winner == null || winner.isEmpty()) return;

                FinishTime time = finishTimesByPlayerName.get(normalizePlayerKey(winner));
                if (time == null) {
                    Long igt = getLongFromKeys(msg, "igtMs", "igt", "igt_ms", "igtMillis", "igt_millis");
                    Long rta = getLongFromKeys(msg, "rtaMs", "rta", "rta_ms", "rtaMillis", "rta_millis");
                    if (igt != null && rta != null) time = new FinishTime(igt, rta);
                }
                if (time == null && normalizePlayerKey(winner).equals(normalizePlayerKey(getClientPlayerName()))) {
                    InGameTimer timer = InGameTimer.getInstance();
                    time = new FinishTime(timer.getInGameTime(false), timer.getRealTimeAttack());
                }
                sendWinnerChat(winner, time);

                state = RaceState.FINISHED;
                resetReadyAfterRace();
                InGameTimer.complete();

            }
            case "start_cancelled", "cancel_start", "starting_cancelled", "start_cancel", "cancel_start_request" -> {
                if (state == RaceState.STARTING) cancelStarting();
            }
            case "start" -> {
                String seed = msg.has("seed") ? msg.get("seed").getAsString() : null;
                String target = msg.has("targetItemId") ? msg.get("targetItemId").getAsString() : null;
                if (seed == null || target == null) return;

                Identifier id = Identifier.tryParse(target);
                if (id == null || !Registries.ITEM.containsId(id)) {
                    lastError = "Invalid targetItemId: " + target;
                    return;
                }

                seedString = seed;
                targetItemId = id;
                int seconds = msg.has("countdown") ? msg.get("countdown").getAsInt() : 5;
                int countdownSeconds = Math.max(0, Math.min(30, seconds));
                startScheduledAt = System.currentTimeMillis() + (long) countdownSeconds * 1000L;
                worldCreationRequested = false;
                state = RaceState.STARTING;
                timerConfigured = false;
                finishTriggered.set(false);
            }
            case "error" -> lastError = msg.has("message") ? msg.get("message").getAsString() : "Unknown server error";
        }
    }

    private void configureTimerForRace() {
        if (timerConfigured) return;
        timerConfigured = true;

        InGameTimer.start(pendingWorldDirectoryName, RunType.fromBoolean(InGameTimerUtils.IS_SET_SEED));
        InGameTimer timer = InGameTimer.getInstance();
        timer.setCategory(RunCategories.CUSTOM, false);
        timer.setUncompleted(false);

        timer.setCategory(RunCategories.CUSTOM, false);
        timer.setUncompleted(false);
    }

    private void parsePlayers(JsonArray playersArray) {
        for (JsonElement el : playersArray) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "Unknown";
            boolean ready = o.has("ready") && o.get("ready").getAsBoolean();
            UUID id = o.has("id") ? safeUuid(o.get("id").getAsString()) : UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            players.add(new PlayerStatus(id, name, ready));
        }
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String normalizeRoomCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static String normalizePlayerKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String getClientPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.getSession() != null ? client.getSession().getUsername() : "Player";
    }

    private record FinishTime(long igtMs, long rtaMs) {}

    private void sendWinnerChat(String winnerName, FinishTime time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;
        ChatHud chat = client.inGameHud.getChatHud();

        MutableText line1 = Text.literal("🏆 ").formatted(Formatting.YELLOW)
                .append(Text.translatable("speedrunigt.race.chat.winner_prefix").formatted(Formatting.WHITE))
                .append(Text.literal(winnerName).formatted(Formatting.GOLD));
        chat.addMessage(line1);

        String igt = time != null ? InGameTimerUtils.timeToStringFormat(time.igtMs()) : "--:--.---";
        String rta = time != null ? InGameTimerUtils.timeToStringFormat(time.rtaMs()) : "--:--.---";
        chat.addMessage(Text.translatable("speedrunigt.race.chat.time", igt, rta).formatted(Formatting.WHITE));
    }

    private static String getStringFromKeys(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try {
                    String value = obj.get(key).getAsString();
                    if (value != null && !value.isEmpty()) return value;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Long getLongFromKeys(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try {
                    JsonPrimitive primitive = obj.getAsJsonPrimitive(key);
                    if (primitive.isNumber()) return primitive.getAsLong();
                    if (primitive.isString()) {
                        String s = primitive.getAsString();
                        if (s == null || s.isEmpty()) continue;
                        try {
                            return Long.parseLong(s);
                        } catch (NumberFormatException ignored) {
                            Long parsed = parseTimeStringToMillis(s);
                            if (parsed != null) return parsed;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Long parseTimeStringToMillis(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("--")) return null;

        // Formats: MM:SS.mmm or H:MM:SS.mmm
        int dot = s.lastIndexOf('.');
        int colon = s.lastIndexOf(':');
        if (dot > 0 && colon > 0 && dot > colon) {
            String msPart = s.substring(dot + 1);
            String left = s.substring(0, dot);

            int ms;
            try {
                String padded = msPart.length() >= 3 ? msPart.substring(0, 3) : (msPart + "000").substring(0, 3);
                ms = Integer.parseInt(padded);
            } catch (Exception ignored) {
                return null;
            }

            String[] parts = left.split(":");
            try {
                if (parts.length == 2) {
                    long minutes = Long.parseLong(parts[0]);
                    long seconds = Long.parseLong(parts[1]);
                    return (minutes * 60L + seconds) * 1000L + ms;
                }
                if (parts.length == 3) {
                    long hours = Long.parseLong(parts[0]);
                    long minutes = Long.parseLong(parts[1]);
                    long seconds = Long.parseLong(parts[2]);
                    return (hours * 3600L + minutes * 60L + seconds) * 1000L + ms;
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private final class WsListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialMessage.append(data);
            if (last) {
                String message = partialMessage.toString();
                partialMessage.setLength(0);
                MinecraftClient.getInstance().execute(() -> handleIncoming(message));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            MinecraftClient.getInstance().execute(() -> {
                connectionStatus = ConnectionStatus.DISCONNECTED;
                RaceSessionManager.this.webSocket = null;
                lastError = "Disconnected: " + reason;
            });
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            MinecraftClient.getInstance().execute(() -> onConnectionFailed(error));
        }
    }
}
