package com.mcprovince.net;

import com.mcprovince.McProvince;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包定义。
 *
 * <p>同步协议带数据版本号：服务端每次接受编辑就把版本 +1，客户端上传时声明自己基于哪个版本改的；
 * 版本对不上（旧客户端、空壳客户端、并发编辑）就拒绝并强制重发全量，杜绝"陈旧快照覆盖服务端"。</p>
 */
public final class ProvincePayloads {

    /** 单块同步大小：256KB，避开单包上限。 */
    public static final int SYNC_CHUNK = 256 * 1024;
    /** 客户端提交的编辑快照上限。 */
    public static final int MAX_EDIT_BYTES = 4 * 1024 * 1024;
    /** 单块最大的可读长度（给 StreamCodec 的解码器用）。 */
    private static final int MAX_CHUNK_BYTES = SYNC_CHUNK * 4;

    private ProvincePayloads() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(EditPayload.ID, EditPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSyncPayload.ID, RequestSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AckPayload.ID, AckPayload.CODEC);
    }

    /** 客户端 -> 服务端：提交一份完整的行政区划快照（NBT 压缩字节）+ 编辑所基于的版本号。 */
    public record EditPayload(int baseVersion, byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<EditPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McProvince.MOD_ID, "edit"));
        public static final StreamCodec<FriendlyByteBuf, EditPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.baseVersion());
                    buf.writeByteArray(payload.data());
                },
                buf -> new EditPayload(buf.readVarInt(), buf.readByteArray(MAX_EDIT_BYTES)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 客户端 -> 服务端：请求当前维度的完整数据（进服 / 换维度后主动兜底）。 */
    public record RequestSyncPayload() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestSyncPayload> ID =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(McProvince.MOD_ID, "request_sync"));
        public static final StreamCodec<FriendlyByteBuf, RequestSyncPayload> CODEC =
                StreamCodec.unit(new RequestSyncPayload());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 服务端 -> 客户端：一个维度的数据分块；last=true 表示这是最后一块；version 是该维度当前数据版本。 */
    public record SyncPayload(ResourceLocation dimension, int version, boolean last, byte[] chunk)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McProvince.MOD_ID, "sync"));
        public static final StreamCodec<FriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeResourceLocation(payload.dimension());
                    buf.writeVarInt(payload.version());
                    buf.writeBoolean(payload.last());
                    buf.writeByteArray(payload.chunk());
                },
                buf -> new SyncPayload(buf.readResourceLocation(), buf.readVarInt(), buf.readBoolean(),
                        buf.readByteArray(MAX_CHUNK_BYTES)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 服务端 -> 客户端（仅编辑者本人）：服务端接受编辑后的新版本号，避免自己的版本号落后。 */
    public record AckPayload(int version) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AckPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McProvince.MOD_ID, "ack"));
        public static final StreamCodec<FriendlyByteBuf, AckPayload> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeVarInt(payload.version()),
                buf -> new AckPayload(buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }
}
