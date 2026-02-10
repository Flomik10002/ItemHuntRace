package com.redlimerl.speedrunigt.race;

import com.redlimerl.speedrunigt.SpeedRunIGTClient;
import com.redlimerl.speedrunigt.race.RaceSessionManager.LeaderboardEntry;
import com.redlimerl.speedrunigt.timer.TimerDrawer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.ColorHelper;

import java.util.List;

@Environment(EnvType.CLIENT)
public class LeaderboardOverlay {

    public static void render(MinecraftClient client, DrawContext context) {
        RaceSessionManager race = RaceSessionManager.getInstance();
        List<LeaderboardEntry> leaderboard = race.getLeaderboard();
        if (leaderboard.isEmpty()) return;

        // Get Timer position to position leaderboard below it
        TimerDrawer drawer = SpeedRunIGTClient.TIMER_DRAWER;
        
        // Default to checking RTA position
        float scale = drawer.getRTAScale();
        if (scale == 0) scale = drawer.getIGTScale();
        if (scale == 0) scale = 1.0f;
        
        // Don't let it be too small
        if (scale < 0.5f) scale = 0.5f;

        float xPos = drawer.getRTA_XPos();
        float yPos = drawer.getRTA_YPos();
        
        // If RTA is hidden/default, maybe try IGT
        if (scale == drawer.getIGTScale()) {
            xPos = drawer.getIGT_XPos();
            yPos = drawer.getIGT_YPos();
        }
        
        // Calculate rough height of the timer to place below
        // This is an estimation. TimerDrawer logic is complex.
        // We will just add a fixed offset relative to Y + padding.
        // A standard line is ~10 * scale.
        // Let's guess the timer is 2-3 lines high?
        // Actually, let's just use the Y position + some offset.
        // If the user said "under timer", implying we should find the bottom.
        // TimerDrawer doesn't expose "getBottomY()".
        // Leaderboard will be drawn at yPos + 30 * scale (approx).
        
        // Alternatively, we can just align it to the right side of the screen if that's what "right side" meant.
        // But "Right side, under timers" implies relative.
        
        // Use RTA positions as anchor if available, else IGT
        // ... (omitted) ...
        
        // Calculate start position
        double startX = xPos + (5 * scale);
        double startY = yPos + (25 * scale); // Offset

        // Scale context like TimerElement
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scale, scale);
        
        // Calculate scaled integer coordinates for rendering
        int x = (int) (startX / scale);
        int y = (int) (startY / scale);
        
        int lineHeight = 10;

        for (LeaderboardEntry entry : leaderboard) {
            String text = entry.name() + " " + entry.time();
            int color = 0xFFCCCCCC; // Light gray
            
            // Check if self
            if (race.getPlayers().stream()
                    .anyMatch(p -> p.name().equalsIgnoreCase(entry.name()) && p.name().equalsIgnoreCase(client.getSession().getUsername()))) {
                color = 0xFF55FF55; // Green
            } else if (leaderboard.indexOf(entry) == 0) {
                 color = 0xFFFFD700; // Gold
            }

            context.drawTextWithShadow(client.textRenderer, text, x, y, color);
            y += lineHeight;
        }
        
        context.getMatrices().popMatrix();
    }
}
