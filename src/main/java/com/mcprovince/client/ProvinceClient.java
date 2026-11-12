package com.mcprovince.client;

import com.mcprovince.ProvinceSelfTest;
import com.mcprovince.data.ProvinceData;
import com.mcprovince.net.ProvincePayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** 客户端总入口：数据、设置、缓存都在这里。 */
public final class ProvinceClient {

    private static final ProvinceData DATA = new ProvinceData();

    private ProvinceClient() {
    }

    public static void init() {
        ProvinceSettings.get().load();
        ProvinceKeys.register();
        ProvinceMapHook.register();
        // 服务端下发的区划同步：收齐一块后整份替换本地数据。
        ClientPlayNetworking.registerGlobalReceiver(ProvincePayloads.SyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (ProvinceClient.data().receiveSync(payload.dimension(), payload.version(),
                            payload.last(), payload.chunk())) {
                        ProvinceEditor.onRemoteSync();
                    }
                }));
        // 服务端接受编辑后的版本号回执：只更新版本号，不动数据和撤销历史。
        ClientPlayNetworking.registerGlobalReceiver(ProvincePayloads.AckPayload.ID, (payload, context) ->
                context.client().execute(() -> ProvinceClient.data().ackVersion(payload.version())));
        ClientTickEvents.END_CLIENT_TICK.register(ProvinceClient::onEndTick);
    }

    public static ProvinceData data() {
        return DATA;
    }

    private static void onEndTick(Minecraft minecraft) {
        String before = DATA.worldKey();
        DATA.checkWorld(minecraft);
        if (!Objects.equals(before, DATA.worldKey())) {
            ProvinceEditor.onWorldChanged();
        }
        // 编辑提交：合并冷却，把整份快照发给服务端（服务端负责落盘和广播）。
        DATA.tickCooldown();
        DATA.tickSync();
        ProvinceTitleTracker.tick(minecraft);
        // 自检里延后的那几条（要等资源包加载完）靠这里跑；平时是空转。
        ProvinceSelfTest.onClientTick(minecraft);
        // 自动进世界验证小地图图层；正式游玩时空转。
        ProvinceSelfTest.tickAutoWorld(minecraft);
    }
}
