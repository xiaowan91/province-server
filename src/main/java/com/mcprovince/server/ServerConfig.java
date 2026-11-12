package com.mcprovince.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端配置：谁能画 / 改区划。
 *
 * <p>{@code editMode}：
 * <ul>
 *   <li>{@code "own"}（默认）——每人只能改自己创建的区划，OP 能改全部；游客可以画自己的；</li>
 *   <li>{@code "all"}——所有人都能改所有人的；</li>
 *   <li>{@code "op"}——只有 OP（权限等级 2）能编辑，其他人只读。</li>
 * </ul>
 * 文件在 {@code config/mc_province_server.json}，改完重启服务器生效。</p>
 */
public final class ServerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");
    private static final String FILE_NAME = "mc_province_server.json";

    /** own = 各改各的（默认）；all = 所有人可改所有；op = 仅 OP。 */
    public String editMode = "own";

    private static ServerConfig instance;

    private ServerConfig() {
    }

    public static synchronized ServerConfig get() {
        if (instance == null) {
            instance = loadOrCreate();
        }
        return instance;
    }

    private static ServerConfig loadOrCreate() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    ServerConfig cfg = gson.fromJson(reader, ServerConfig.class);
                    if (cfg != null) {
                        return cfg;
                    }
                }
            }
            ServerConfig cfg = new ServerConfig();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                gson.toJson(cfg, writer);
            }
            LOGGER.info("已生成服务端配置：{}", file);
            return cfg;
        } catch (IOException e) {
            LOGGER.warn("读取服务端配置失败，使用默认值（所有玩家可编辑）", e);
            return new ServerConfig();
        }
    }
}
