// race-server.js
const WebSocket = require('ws');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function heartbeat() {
    this.isAlive = true;
}

const wss = new WebSocket.Server({ port: 8080 });
console.log("Race server running on ws://0.0.0.0:8080");

const ITEMS_FILE = path.join(__dirname, 'items.txt');

function loadItems() {
    try {
        const raw = fs.readFileSync(ITEMS_FILE, 'utf-8');
        const items = raw
            .split('\n')
            .map(l => l.trim())
            .filter(l => l.startsWith('minecraft:') && l.length > "minecraft:".length);
        console.log(`Loaded ${items.length} items`);
        return items.length ? items : ["minecraft:diamond"];
    } catch {
        return ["minecraft:diamond"];
    }
}

let ITEM_POOL = loadItems();

function randomRoomCode() {
    const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    return Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join('');
}

function randomSeed() {
    return Math.floor(Math.random() * Number.MAX_SAFE_INTEGER).toString();
}

function randomItem() {
    return ITEM_POOL[Math.floor(Math.random() * ITEM_POOL.length)];
}

function broadcast(room, data) {
    const msg = JSON.stringify(data);
    room.players.forEach(p => {
        if (p.ws.readyState === WebSocket.OPEN) p.ws.send(msg);
    });
}

function updateRoom(room) {
    broadcast(room, {
        type: "room_update",
        roomCode: room.code,
        state: room.state,
        players: room.players.map(p => ({
            id: p.id,
            name: p.name,
            ready: p.ready,
            isLeader: p.id === room.leaderId
        }))
    });
}

const rooms = new Map();

function resetRoomToLobby(room) {
    room.state = "lobby";
    room.seed = null;
    room.targetItemId = null;
    room.startedAt = 0;
    room.players.forEach(p => p.ready = false);
    room.results = [];
    updateRoom(room);
    console.log(`Room ${room.code} reset to LOBBY`);
}

function safeString(x, max = 64) {
    if (typeof x !== 'string') return '';
    const s = x.trim();
    return s.length > max ? s.slice(0, max) : s;
}

function safeBool(x) {
    return !!x;
}

function safeInt(x, min, max) {
    const n = Number(x);
    if (!Number.isFinite(n)) return min;
    const v = Math.floor(n);
    return Math.max(min, Math.min(max, v));
}

function roomOrError(ws, player) {
    if (!player) return null;
    const room = rooms.get(player.roomCode);
    if (!room) {
        ws.send(JSON.stringify({ type: "error", message: "Room not found" }));
        return null;
    }
    return room;
}

wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', heartbeat);

    let player = null;

    ws.on('message', (message) => {
        let data;
        try { data = JSON.parse(message); } catch { return; }
        if (!data || typeof data.type !== 'string') return;

        switch (data.type) {

            case "create_room": {
                const code = randomRoomCode();
                const room = {
                    code,
                    players: [],
                    leaderId: null,
                    state: "lobby", // lobby | starting | running
                    seed: null,
                    targetItemId: null,
                    startedAt: 0,
                    results: []
                };
                rooms.set(code, room);

                const name = safeString(data.playerName, 32) || "Player";
                player = { id: crypto.randomUUID(), name, ready: false, ws, roomCode: code };
                room.players.push(player);
                room.leaderId = player.id; // Creator is leader

                ws.send(JSON.stringify({ type: "room_created", roomCode: code, players: room.players }));
                updateRoom(room);
                console.log(`Room ${code} created by ${name}`);
                break;
            }

            case "join_room": {
                const code = safeString(data.roomCode, 12).toUpperCase().replaceAll(' ', '');
                const room = rooms.get(code);
                if (!room) return ws.send(JSON.stringify({ type: "error", message: "Room not found" }));

                const name = safeString(data.playerName, 32) || "Player";
                player = { id: crypto.randomUUID(), name, ready: false, ws, roomCode: room.code };
                room.players.push(player);

                // If room has no leader (shouldn't happen but for safety), assign
                if (!room.leaderId) room.leaderId = player.id;

                ws.send(JSON.stringify({ type: "room_joined", roomCode: room.code, players: room.players }));
                updateRoom(room);
                console.log(`${name} joined room ${room.code}`);
                break;
            }

            case "leave_room": {
                if (!player) return;
                const room = rooms.get(player.roomCode);
                if (!room) { player = null; return; }

                room.players = room.players.filter(p => p !== player);
                updateRoom(room);
                console.log(`${player.name} left room ${room.code}`);

                if (room.players.length === 0) {
                    rooms.delete(room.code);
                    console.log(`Room ${room.code} deleted (empty)`);
                } else if (room.leaderId === player.id) {
                    // Leader left, assign new leader
                    room.leaderId = room.players[0].id;
                    console.log(`Player ${player.name} (leader) left, new leader is ${room.players[0].name}`);
                    updateRoom(room);
                }
                player = null;
                break;
            }

            case "reset_lobby": {
                const room = roomOrError(ws, player);
                if (!room) return;

                // Only leader can reset
                if (player.id !== room.leaderId) return;

                resetRoomToLobby(room);
                break;
            }

            case "ready": {
                const room = roomOrError(ws, player);
                if (!room) return;

                // Allow toggling ready only in lobby (and optionally in running if you want)
                if (room.state !== "lobby") return;

                player.ready = safeBool(data.ready);
                updateRoom(room);
                break;
            }

            case "start_request": {
                const room = roomOrError(ws, player);
                if (!room) return;

                // Only allow from lobby
                if (room.state !== "lobby") return;

                // Only leader can start
                if (player.id !== room.leaderId) return;

                // Require everyone ready
                if (!room.players.length || !room.players.every(p => p.ready)) return;

                room.state = "starting";
                room.seed = randomSeed();
                room.targetItemId = randomItem();
                room.results = [];
                updateRoom(room);

                const countdown = safeInt(data.countdown ?? 10, 0, 30);

                broadcast(room, {
                    type: "start",
                    seed: room.seed,
                    targetItemId: room.targetItemId,
                    countdown
                });

                console.log(`Room ${room.code} STARTING seed=${room.seed} target=${room.targetItemId} countdown=${countdown}s`);

                setTimeout(() => {
                    // If nobody cancelled, move to running
                    if (!rooms.has(room.code)) return;
                    if (room.state === "starting") {
                        room.state = "running";
                        room.startedAt = Date.now();
                        updateRoom(room);
                        console.log(`Room ${room.code} is now RUNNING`);
                    }
                }, countdown * 1000);

                break;
            }

            case "cancel_start":
            case "cancel_start_request":
            case "start_cancel":
            case "stop_start":
            case "abort_start": {
                const room = roomOrError(ws, player);
                if (!room) return;
                if (room.state !== "starting") return;

                // Only leader can cancel
                if (player.id !== room.leaderId) return;

                // Stop start logic:
                // 1. Reset state to lobby
                // 2. Only unready the leader
                // 3. Keep others ready
                // 4. Reset seed/target

                room.state = "lobby";
                room.seed = null;
                room.targetItemId = null;

                // Only leader becomes unready
                player.ready = false;

                broadcast(room, { type: "start_cancelled" });
                updateRoom(room);
                console.log(`Start cancelled by leader ${player.name} in room ${room.code}`);
                break;
            }

            // MULTI-WINNER RESULTS (no room finishing here)
            case "finish": {
                const room = roomOrError(ws, player);
                if (!room) return;

                if (room.state !== "running") return;

                const reason = safeString(data.reason, 32).toLowerCase(); // "target_obtained" | "death"
                const rtaMs = safeInt(data.rtaMs, 0, 2_147_483_647);
                const igtMs = safeInt(data.igtMs, 0, 2_147_483_647);

                // Store result
                room.results = room.results || [];

                let rank = -1;
                let status = "finished";

                if (reason === "death") {
                    status = "eliminated";
                    rank = 999; // Or unranked
                } else {
                    // Only rank finishers
                    const finishers = room.results.filter(r => r.status === "finished").length;
                    rank = finishers + 1;
                }

                const resultEntry = {
                    name: player.name,
                    reason: reason || "unknown",
                    status: status,
                    rtaMs,
                    igtMs,
                    rank,
                    at: Date.now()
                };

                // Remove existing result for this player if any (updates)
                room.results = room.results.filter(r => r.name !== player.name);
                room.results.push(resultEntry);

                // Sort results: Finished (by rank) -> Eliminated
                room.results.sort((a, b) => {
                    if (a.status === "finished" && b.status !== "finished") return -1;
                    if (a.status !== "finished" && b.status === "finished") return 1;
                    if (a.status === "finished") return a.rank - b.rank;
                    return 0;
                });

                broadcast(room, {
                    type: "player_result",
                    player: player.name,
                    reason: reason || "unknown",
                    rtaMs,
                    igtMs,
                    rank
                });

                if (rank === 1) {
                    broadcast(room, {
                        type: "winner",
                        player: player.name,
                        rtaMs,
                        igtMs
                    });
                    console.log(`WINNER room=${room.code} player=${player.name}`);
                }

                console.log(`RESULT room=${room.code} player=${player.name} reason=${reason} rta=${rtaMs} igt=${igtMs}`);
                break;
            }

            // ADVANCEMENT BROADCAST
            case "advancement": {
                const room = roomOrError(ws, player);
                if (!room) return;
                if (room.state !== "running") return;

                const advancementId = safeString(data.advancementId, 128);
                if (!advancementId) return;

                broadcast(room, {
                    type: "advancement",
                    playerName: player.name,
                    advancementId
                });

                console.log(`ADV room=${room.code} player=${player.name} adv=${advancementId}`);
                break;
            }

            // Optional: reload item pool without restart (server-side admin use)
            case "reload_items": {
                ITEM_POOL = loadItems();
                ws.send(JSON.stringify({ type: "items_reloaded", count: ITEM_POOL.length }));
                break;
            }

            // HEALTH CHECK PING
            case "ping": {
                ws.send(JSON.stringify({ type: "pong" }));
                break;
            }

            default: {
                // ignore unknown
                break;
            }
        }
    });

    ws.on('close', () => {
        if (!player) return;
        const room = rooms.get(player.roomCode);
        if (!room) return;

        room.players = room.players.filter(p => p !== player);
        updateRoom(room);
        console.log(`${player.name} disconnected from room ${room.code}`);

        if (room.players.length === 0) {
            rooms.delete(room.code);
            console.log(`Room ${room.code} deleted (empty)`);
        } else if (room.leaderId === player.id) {
            // Leader left, assign new leader
            room.leaderId = room.players[0].id;
            console.log(`Player ${player.name} (leader) disconnected, new leader is ${room.players[0].name}`);
            updateRoom(room);
        }
    });
});

// Heartbeat interval (every 5 seconds)
const interval = setInterval(function ping() {
    wss.clients.forEach(function each(ws) {
        if (ws.isAlive === false) return ws.terminate();

        ws.isAlive = false;
        ws.ping();
    });
}, 10000);

wss.on('close', function close() {
    clearInterval(interval);
});
