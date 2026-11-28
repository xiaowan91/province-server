# Province Server Fork — 改动说明

本仓库是 [Province](https://github.com/KaibuchiShuuki/Province)（原作者 **Shuuki_**，MIT 协议）的第三方改造版，新增了服务端同步与区划归属权限。

## 与原版的区别

### 1. Minecraft 版本
- 原版支持：1.20.1 / 1.21.1 / 1.21.11 / 26.2
- 本 fork：**1.21.7 + Fabric**（基于作者的 1.21.11 分支移植）

### 2. 服务端同步（核心改动）
- 原版：纯客户端，数据只存在每个玩家自己的机器上
- 本 fork：数据由**服务端存储**（`<存档>/province/<维度>.nbt`），玩家进服 / 换维度时自动同步
- 多人联机时，所有玩家共享同一份行政区划数据；地图图层、进入区划弹地名等功能对所有玩家生效

### 3. 归属权限
- 新增 `editMode` 配置（`config/mc_province_server.json`）：
  - `own`（默认）：每人只能修改自己创建的区划，OP 可改全部；游客仍可绘制自己的区划
  - `all`：所有人可改所有
  - `op`：只有 OP 能编辑
- 服务端强制校验，客户端无法伪造归属

### 4. 数据安全
- 同步协议带数据版本号：旧客户端 / 过期快照无法覆盖服务端
- 写入前自动备份 `.bak`，误覆盖可恢复
- 每 30 秒自动保存 + 关服保存

### 5. 移植适配（1.21.11 → 1.21.7）
- 输入事件 API（`KeyEvent`/`MouseButtonEvent` → 经典 `(double, double, int)` 等签名）
- `Identifier` → `ResourceLocation`
- `setScreenAndShow` → `setScreen`
- `NbtAccounter.defaultQuota` → `unlimitedHeap`
- `ResourceKey.identifier()` → `location()`
- `KeyMapping.Category` → 字符串分类
- Mixin 注入点按 1.21.7 实际方法签名调整（已用 mojmap jar 核对）

## 安装

- 客户端：本 mod + Fabric API + Xaero's World Map（1.21.7）+ Xaero's Minimap（可选）
- 服务端：本 mod + Fabric API（Xaero 系列不需要）
- **两边装同一个 jar**（双端 mod，`environment: "*"`）

## 声明

- 原始设计、大部分代码与文档来自 Shuuki_（MIT 协议），版权归原作者所有
- 本 fork 由 AI 辅助（DeepSeek Harness）完成移植与联机改造
- 非官方版本；如原作者发布官方联机版，请以其为准
