package com.mcprovince.server;

import com.mcprovince.core.ProvinceStore;
import com.mcprovince.data.ProvinceNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 服务端权威的行政区划存储：每个维度一份。
 *
 * <p>存在存档目录的 {@code province/<维度>.nbt} 下——通过 {@code getWorldPath(ROOT)} 动态获取，
 * 每个存档完全隔离，绝不写到全局配置目录；和旧版客户端单人存档的路径一致，旧数据直接沿用。</p>
 *
 * <p>每个维度还带一个数据版本号：每次接受编辑 +1，用于拒绝基于旧数据的提交。</p>
 */
public final class ServerProvinceData {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");
    private static final String DIR_NAME = "province";

    private static final Map<ServerLevel, ProvinceStore> STORES = new HashMap<>();
    private static final Map<ServerLevel, Integer> VERSIONS = new HashMap<>();

    private ServerProvinceData() {
    }

    public static synchronized ProvinceStore get(ServerLevel level) {
        return STORES.computeIfAbsent(level, ServerProvinceData::load);
    }

    /** 当前维度的数据版本号。 */
    public static synchronized int version(ServerLevel level) {
        return VERSIONS.getOrDefault(level, 0);
    }

    /** 用客户端提交的数据整体替换当前维度的数据，版本 +1，并立刻落盘；返回新版本号。 */
    public static synchronized int replace(ServerLevel level, ProvinceStore store) {
        STORES.put(level, store);
        int version = VERSIONS.getOrDefault(level, 0) + 1;
        VERSIONS.put(level, version);
        save(level, store);
        return version;
    }

    public static synchronized void saveAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ProvinceStore store = STORES.get(level);
            if (store != null) {
                save(level, store);
            }
        }
    }

    private static void save(ServerLevel level, ProvinceStore store) {
        Path file = file(level);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 覆盖前先留一份 .bak：万一以后又被坏数据覆盖，直接把 .bak 改名回去就能救回来。
            if (Files.exists(file)) {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            NbtIo.writeCompressed(ProvinceNbt.write(store), file);
            LOGGER.info("已保存行政区划 {} 个 -> {}", store.size(), file);
        } catch (IOException e) {
            LOGGER.error("保存行政区划失败：{}", file, e);
        }
    }

    private static ProvinceStore load(ServerLevel level) {
        ProvinceStore store = new ProvinceStore();
        Path file = file(level);
        if (!Files.exists(file)) {
            return store;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ProvinceNbt.read(tag, store);
            LOGGER.info("载入服务端行政区划 {} 个（{}）", store.size(), level.dimension().location());
        } catch (IOException e) {
            LOGGER.error("读取行政区划失败：{}", file, e);
        }
        return store;
    }

    public static Path file(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return root.resolve(DIR_NAME).resolve(dimKey(level) + ".nbt");
    }

    private static String dimKey(ServerLevel level) {
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
