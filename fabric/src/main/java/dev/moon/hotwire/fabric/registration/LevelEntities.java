package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

final class LevelEntities {
    private LevelEntities() {
    }

    static List<Object> findAll() {
        List<Object> found = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != null) {
            for (Entity entity : level.entitiesForRendering()) {
                found.add(entity);
            }
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            for (ServerLevel one : server.getAllLevels()) {
                for (Entity entity : one.getAllEntities()) {
                    found.add(entity);
                }
            }
        }
        return found;
    }
}
