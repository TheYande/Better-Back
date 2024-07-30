package com.yande.betterback;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;

import static net.minecraft.server.command.CommandManager.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BetterBack implements ModInitializer {
    public static final Map<UUID, PlayerLocation> playerLastLocations = new HashMap<>();
    public static final Map<UUID, PlayerLocation> playerBackLocations = new HashMap<>();
    private static final double TELEPORT_THRESHOLD = 100.0; // Distance threshold for teleport detection

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Vec3d currentPos = player.getPos();
                RegistryKey<World> currentWorld = player.getWorld().getRegistryKey();
                UUID playerUUID = player.getUuid();

                if (playerLastLocations.containsKey(playerUUID)) {
                    PlayerLocation lastLocation = playerLastLocations.get(playerUUID);

                    if (lastLocation != null && !lastLocation.getWorld().equals(currentWorld)) {
                        // Player changed dimension
                        playerBackLocations.put(playerUUID, lastLocation);
                    } else if (lastLocation != null && lastLocation.getPos()
                            .squaredDistanceTo(currentPos) >= TELEPORT_THRESHOLD * TELEPORT_THRESHOLD) {
                        // Player moved significantly in the same dimension
                        playerBackLocations.put(playerUUID, lastLocation);
                    }
                }
                playerLastLocations.put(playerUUID, new PlayerLocation(currentWorld, currentPos));
            }
        });

        CommandRegistrationCallback.EVENT
                .register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("back")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            UUID playerUUID = player.getUuid();

                            if (playerBackLocations.containsKey(playerUUID)) {
                                PlayerLocation backLocation = playerBackLocations.get(playerUUID);
                                if (backLocation != null) {
                                    ServerWorld world = player.getServer().getWorld(backLocation.getWorld());
                                    if (world != null) {
                                        BlockPos safePos = findSafeLocation(world, backLocation.getPos());
                                        if (safePos != null) {
                                            player.teleport(world, safePos.getX(), safePos.getY(), safePos.getZ(),
                                                    player.getYaw(), player.getPitch());
                                            player.sendMessage(
                                                    Text.literal("Teleported back to your last safe location.").styled(
                                                            style -> style.withColor(TextColor.fromRgb(0x00FF00))),
                                                    false);
                                        } else {
                                            player.sendMessage(
                                                    Text.literal("No safe location found.").styled(
                                                            style -> style.withColor(TextColor.fromRgb(0xFF0000))),
                                                    false);
                                        }
                                    } else {
                                        player.sendMessage(Text.literal("World not found.")
                                                .styled(style -> style.withColor(TextColor.fromRgb(0xFF0000))), false);
                                    }
                                } else {
                                    player.sendMessage(Text.literal("No previous location found.")
                                            .styled(style -> style.withColor(TextColor.fromRgb(0xFF0000))), false);
                                }
                            } else {
                                player.sendMessage(Text.literal("No previous location found.")
                                        .styled(style -> style.withColor(TextColor.fromRgb(0xFF0000))), false);
                            }
                            return 1;
                        })));
    }

    private static class PlayerLocation {
        private final RegistryKey<World> world;
        private final Vec3d pos;

        public PlayerLocation(RegistryKey<World> world, Vec3d pos) {
            this.world = world;
            this.pos = pos;
        }

        public RegistryKey<World> getWorld() {
            return world;
        }

        public Vec3d getPos() {
            return pos;
        }
    }

    private static final int MAX_RADIUS = 512; // Maximum search radius to prevent infinite loops

    private BlockPos findSafeLocation(ServerWorld world, Vec3d pos) {
        BlockPos initialPos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        BlockPos safePos = null;
        int radius = 1;
        Set<BlockPos> visitedPositions = new HashSet<>();
        while (radius <= MAX_RADIUS) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos checkPos = initialPos.add(x, y, z);

                        if (!visitedPositions.contains(checkPos)) {
                            visitedPositions.add(checkPos);
                            if (checkIfSafe(world, checkPos)) {
                                return checkPos; // Return immediately after finding the first safe position
                            }
                        }
                    }
                }
            }
            radius++;
        }
        return safePos; // Return null if no safe location is found
    }

    private boolean checkIfSafe(ServerWorld world, BlockPos pos) {
        BlockState stateBelow = world.getBlockState(pos.down());
        return world.isAir(pos) &&
                world.isAir(pos.up()) &&
                !world.isAir(pos.down()) &&
                stateBelow.isSolidBlock(world, pos.down()) &&
                !world.getBlockState(pos.down()).getBlock().equals(Blocks.LAVA) &&
                !world.getBlockState(pos.down()).getBlock().equals(Blocks.BEDROCK);
    }

}
