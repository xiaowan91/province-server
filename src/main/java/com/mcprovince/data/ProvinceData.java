package com.mcprovince.data;

import com.mcprovince.core.ProvinceStore;
import com.mcprovince.net.ProvincePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端的行政区划数据。服务端权威：
 *
 * <ul>
 *   <li>进服 / 换维度时，服务端把当前维度的数据分块发过来（{@link #receiveSync}），
 *       客户端整份替换本地 store；</li>
 *   <li>玩家编辑后，客户端把手里的整份快照发给服务端（{@link #tickSync}），
 *       由服务端校验、落盘、广播给其他玩家；</li>
 *   <li>旧版本存在存档 {@code province/*.nbt} 里的数据现在由服务端直接读写，
 *       单人存档升级后不会丢区划。</li>
 * </ul>
 */
public final class ProvinceData {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");
    private static final String DIR_NAME = "province";
    private static final String EXPORT_DIR = "exports";

    /** 每 N tick 最多提交一次编辑，合并连续绘制产生的多次 markDirty。 */
    private static final int SYNC_COOLDOWN_TICKS = 5;

    /** 收到一半的分块缓冲：维度 -> 已到达的字节。 */
    private static final Map<ResourceLocation, ByteArrayOutputStream> RECEIVING = new HashMap<>();

    private ProvinceStore store = new ProvinceStore();
    private Path file;
    private String worldKey;
    private boolean dirty;
    private boolean syncPending;
    private int syncCooldown;
    /** 是否已经收到过服务端的完整数据；收到之前绝不把本地数据传上去，防止空数据覆盖服务器。 */
    private boolean synced;
    /** 本地数据对应的服务端版本号；上传编辑时带上，服务端版本对不上就拒绝。 */
    private int syncedVersion;

    public ProvinceStore store() {
        return this.store;
    }

    public Path file() {
        return this.file;
    }

    public String worldKey() {
        return this.worldKey;
    }

    /** 编辑过：标记待提交。真正的网络发送在 {@link #tickSync()} 里合并。 */
    public void markDirty() {
        this.dirty = true;
        this.syncPending = true;
    }

    /** 每 tick 调用：换世界 / 换维度时清空本地数据，等服务端重新下发。 */
    public void checkWorld(Minecraft minecraft) {
        String key = worldKey(minecraft);
        if (key.equals(this.worldKey)) {
            return;
        }
        this.worldKey = key;
        this.store = new ProvinceStore();
        this.dirty = false;
        this.syncPending = false;
        this.synced = false;
        this.syncedVersion = 0;
        // 断线重连 / 换维度时，上一轮没收齐的分块必须清掉，否则会和新数据拼成坏包。
        RECEIVING.clear();
        this.file = dataDir(minecraft).resolve(dimensionKey(minecraft) + ".nbt");
        // 主动向服务端要一份完整数据：JOIN 包可能在本方法之前就到、被这里清掉，
        // 不能只指望服务端推，必须自己拉一次兜底。
        if (ClientPlayNetworking.canSend(ProvincePayloads.RequestSyncPayload.ID)) {
            ClientPlayNetworking.send(new ProvincePayloads.RequestSyncPayload());
        }
    }

    /** 数据目录（导出图片也放这里；单人存档在存档目录，联机在配置目录）。 */
    public static Path dataDir(Minecraft minecraft) {
        IntegratedServer integrated = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (integrated != null) {
            Path root = integrated.getWorldPath(LevelResource.ROOT);
            if (root != null) {
                return root.resolve(DIR_NAME);
            }
        }
        return FabricLoader.getInstance().getConfigDir()
                .resolve(DIR_NAME)
                .resolve("worlds")
                .resolve(minecraft == null ? "default" : baseKey(minecraft));
    }

    /** 地图导出图片的落地目录。 */
    public static Path exportDir(Minecraft minecraft) {
        return dataDir(minecraft).resolve(EXPORT_DIR);
    }

    /** 整份数据的深拷贝快照，撤销 / 重做用。 */
    public CompoundTag snapshot() {
        return ProvinceNbt.write(this.store);
    }

    /** 用快照整体替换当前数据（撤销 / 重做）。 */
    public void applySnapshot(CompoundTag snapshot) {
        ProvinceStore fresh = new ProvinceStore();
        ProvinceNbt.read(snapshot.copy(), fresh);
        this.store = fresh;
        this.dirty = true;
        this.syncPending = true;
    }

    /**
     * 收到服务端的一块同步数据；返回 true 表示已经应用完整快照。
     *
     * <p>分块按 TCP 顺序到达：先攒字节，收到 last 再解析替换。</p>
     */
    public boolean receiveSync(ResourceLocation dimension, int version, boolean last, byte[] chunk) {
        ByteArrayOutputStream buffer = RECEIVING.computeIfAbsent(dimension, key -> new ByteArrayOutputStream());
        try {
            buffer.write(chunk);
        } catch (IOException e) {
            RECEIVING.remove(dimension);
            return false;
        }
        if (!last) {
            return false;
        }
        RECEIVING.remove(dimension);
        return applyRemote(buffer.toByteArray(), version);
    }

    private boolean applyRemote(byte[] data, int version) {
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            ProvinceStore fresh = new ProvinceStore();
            ProvinceNbt.read(tag, fresh);
            this.store = fresh;
            this.dirty = false;
            this.syncPending = false;
            this.synced = true;
            this.syncedVersion = version;
            // 关键：立刻把世界键更新成当前的世界。否则下一 tick 的 checkWorld 会发现「世界变了」，
            // 把刚同步到的数据又清掉（JOIN 同步与 checkWorld 的时序竞争）。
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                this.worldKey = worldKey(minecraft);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("解析服务端同步数据失败", e);
            return false;
        }
    }

    /** 服务端接受编辑后回传的新版本号：直接同步版本号，不替换数据、不清撤销历史。 */
    public void ackVersion(int version) {
        this.syncedVersion = version;
    }

    /** 每 tick 调用：有未提交的编辑就合并后发给服务端。 */
    public void tickSync() {
        if (!this.syncPending || this.syncCooldown > 0) {
            return;
        }
        // 还没收到服务端数据之前，本地只是空壳（或旧数据），绝不上传，避免覆盖服务器。
        if (!this.synced) {
            return;
        }
        this.syncCooldown = SYNC_COOLDOWN_TICKS;
        this.syncPending = false;
        byte[] data = serialize();
        if (data.length > ProvincePayloads.MAX_EDIT_BYTES) {
            LOGGER.warn("编辑快照过大（{} 字节），未提交", data.length);
            return;
        }
        if (ClientPlayNetworking.canSend(ProvincePayloads.EditPayload.ID)) {
            ClientPlayNetworking.send(new ProvincePayloads.EditPayload(this.syncedVersion, data));
        }
    }

    /** 每 tick 递减冷却。 */
    public void tickCooldown() {
        if (this.syncCooldown > 0) {
            this.syncCooldown--;
        }
    }

    private byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(ProvinceNbt.write(this.store), out);
        } catch (IOException e) {
            LOGGER.error("序列化行政区划失败", e);
        }
        return out.toByteArray();
    }

    /** 兼容旧接口：数据保存已由服务端负责，这里不再写本地文件。 */
    public void save() {
    }

    /** 兼容旧接口：数据加载由服务端同步负责。 */
    public void load() {
    }

    /** 兼容旧接口：数据保存已由服务端负责。 */
    public void saveNow() {
    }

    /**
     * 当前世界 + 维度的键，用来判断是否需要清空数据。
     *
     * <p>主世界、下界、末地各算各的：同一套坐标在三个维度里根本不是一回事。</p>
     */
    public static String worldKey(Minecraft minecraft) {
        if (minecraft == null) {
            return "default";
        }
        return baseKey(minecraft) + "__" + dimensionKey(minecraft);
    }

    private static String baseKey(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "server_" + sanitize(server.ip);
        }
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null) {
            Path directory = integrated.getServerDirectory();
            if (directory != null && directory.getFileName() != null) {
                return "world_" + sanitize(directory.getFileName().toString());
            }
        }
        return "default";
    }

    /** 当前维度在文件名里的短名。 */
    public static String dimensionKey(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return "unknown";
        }
        return sanitize(level.dimension().location().toString());
    }

    private static String sanitize(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean keep = Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_';
            builder.append(keep ? c : '_');
        }
        return builder.toString();
    }
}
