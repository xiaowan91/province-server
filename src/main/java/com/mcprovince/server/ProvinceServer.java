package com.mcprovince.server;

import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.data.ProvinceNbt;
import com.mcprovince.net.ProvincePayloads;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 服务端逻辑：数据存在服务端存档里，玩家进服 / 换维度时下发当前维度的数据，
 * 收到玩家的编辑快照后校验版本与权限、落盘并广播给同一维度的其他玩家。
 */
public final class ProvinceServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");

    private static int autoSaveTicks;

    private ProvinceServer() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ServerConfig.get());
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerProvinceData::saveAll);
        // 每 30 秒兜底存一次：就算关服事件没触发（崩溃、强退），最多丢 30 秒的编辑。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++autoSaveTicks >= 600) {
                autoSaveTicks = 0;
                ServerProvinceData.saveAll(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(handler.player));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD
                .register((player, origin, destination) -> syncTo(player));
        ServerPlayNetworking.registerGlobalReceiver(ProvincePayloads.EditPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> handleEdit(player, payload));
        });
        // 客户端换世界 / 换维度后主动要一份数据（兜底 JOIN 包丢失或与客户端时序错位）。
        ServerPlayNetworking.registerGlobalReceiver(ProvincePayloads.RequestSyncPayload.ID,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    context.server().execute(() -> syncTo(player));
                });
    }

    /** 把玩家当前维度的整份数据分块发过去（带当前版本号）。 */
    private static void syncTo(ServerPlayer player) {
        ServerLevel level = player.level();
        ProvinceStore store = ServerProvinceData.get(level);
        int version = ServerProvinceData.version(level);
        LOGGER.info("向 {} 同步 {} 个行政区划（{}，版本 {}）", player.getGameProfile().getName(),
                store.size(), level.dimension().location(), version);
        sendChunks(player, level.dimension().location(), version, serialize(store));
    }

    private static void sendChunks(ServerPlayer player, ResourceLocation dimension, int version, byte[] data) {
        int total = Math.max(1, (data.length + ProvincePayloads.SYNC_CHUNK - 1) / ProvincePayloads.SYNC_CHUNK);
        for (int i = 0; i < total; i++) {
            int start = i * ProvincePayloads.SYNC_CHUNK;
            int end = Math.min(data.length, start + ProvincePayloads.SYNC_CHUNK);
            byte[] part = Arrays.copyOfRange(data, start, end);
            ServerPlayNetworking.send(player,
                    new ProvincePayloads.SyncPayload(dimension, version, i == total - 1, part));
        }
    }

    /** 广播给同一维度里除发送者以外的所有人。 */
    private static void broadcast(ServerLevel level, ServerPlayer except, int version) {
        byte[] data = serialize(ServerProvinceData.get(level));
        for (ServerPlayer player : level.players()) {
            if (player != except) {
                sendChunks(player, level.dimension().location(), version, data);
            }
        }
    }

    private static void handleEdit(ServerPlayer player, ProvincePayloads.EditPayload payload) {
        ServerConfig cfg = ServerConfig.get();
        if (payload.data().length > ProvincePayloads.MAX_EDIT_BYTES) {
            LOGGER.warn("编辑快照过大（{} 字节），已拒绝", payload.data().length);
            syncTo(player);
            return;
        }
        ServerLevel level = player.level();
        int currentVersion = ServerProvinceData.version(level);
        if (payload.baseVersion() != currentVersion) {
            // 客户端基于的数据已经过期（旧客户端、空壳客户端、并发编辑）——拒绝，强制拿最新全量，
            // 这样过期快照永远覆盖不了服务端。
            LOGGER.warn("拒绝 {} 的过期编辑：基于版本 {}，当前版本 {}", player.getGameProfile().getName(),
                    payload.baseVersion(), currentVersion);
            syncTo(player);
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(payload.data()),
                    NbtAccounter.unlimitedHeap());
            ProvinceStore incoming = new ProvinceStore();
            ProvinceNbt.read(tag, incoming);
            ProvinceStore current = ServerProvinceData.get(level);
            String mode = cfg.editMode;
            boolean op = player.hasPermissions(2);
            String uuid = player.getStringUUID();

            // 归属校验：改别人的块 / 删别人的块 / 改归属 → 拒绝。
            for (Province p : incoming.allSorted()) {
                Province old = current.byId(p.id());
                if (old == null) {
                    // 新块：归属一律记到提交者名下（不信客户端自报）。
                    p.setOwner(uuid);
                    continue;
                }
                if (!old.owner().equals(p.owner())) {
                    reject(player, "归属信息被篡改");
                    return;
                }
                if (!old.sameContent(p) && !canEdit(mode, op, uuid, old)) {
                    reject(player, "你没有权限修改「" + regionLabel(old) + "」");
                    return;
                }
            }
            for (Province old : current.allSorted()) {
                if (incoming.byId(old.id()) == null && !canEdit(mode, op, uuid, old)) {
                    reject(player, "你没有权限删除「" + regionLabel(old) + "」");
                    return;
                }
            }

            LOGGER.info("接受 {} 的编辑（{} 个区划）", player.getGameProfile().getName(), incoming.size());
            int newVersion = ServerProvinceData.replace(level, incoming);
            // 告诉编辑者本人新版本号（他本地数据已经是最新，不需要重发全量数据）。
            ServerPlayNetworking.send(player, new ProvincePayloads.AckPayload(newVersion));
            broadcast(level, player, newVersion);
        } catch (IOException e) {
            LOGGER.warn("解析编辑快照失败，已回滚", e);
            syncTo(player);
        }
    }

    /** 权限判定：all 全放行；op 仅管理员；own（默认）管理员 + 本人。 */
    private static boolean canEdit(String mode, boolean op, String uuid, Province region) {
        if ("all".equalsIgnoreCase(mode)) {
            return true;
        }
        if (op) {
            return true;
        }
        if ("op".equalsIgnoreCase(mode)) {
            return false;
        }
        String owner = region.owner();
        return !owner.isEmpty() && owner.equals(uuid);
    }

    private static String regionLabel(Province region) {
        if (!region.nameZh().isEmpty()) {
            return region.nameZh();
        }
        return region.nameEn().isEmpty() ? "未命名" : region.nameEn();
    }

    private static void reject(ServerPlayer player, String why) {
        player.sendSystemMessage(Component.literal("§c编辑被拒绝：" + why));
        syncTo(player);
    }

    /** 把存储序列化成压缩 NBT 字节。 */
    public static byte[] serialize(ProvinceStore store) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(ProvinceNbt.write(store), out);
        } catch (IOException e) {
            LOGGER.error("序列化行政区划失败", e);
        }
        return out.toByteArray();
    }
}
