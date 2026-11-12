# Province 服务端版 — 进度与设计

> 给将来的会话看：读完这一份就能无缝续接。项目根目录 `F:\Xiaowanwan\province_1217\Province`。

## 已完成

### 阶段 1：1.21.7 移植（已完成）
- 基线：作者 GitHub `1.21.11` 分支（最接近 1.21.7）
- 修了 57 处 1.21.9+ 新 API 与 1.21.7 的差异：
  - `Identifier` → `ResourceLocation`
  - 输入事件重构回经典签名：`mouseClicked(double,double,int)` / `keyPressed(int,int,int)` / `mouseDragged(double,double,int,double,double)` / `mouseReleased(double,double,int)`
  - `setScreenAndShow` → `setScreen`
  - `net.minecraft.util.Util` → `net.minecraft.Util`
  - `NbtAccounter.defaultQuota()` → `unlimitedHeap()`
  - `ResourceKey.identifier()` → `location()`
  - `KeyMapping.Category` → 字符串分类
  - mixin 目标：`KeyboardHandler.keyPress(long,int,int,int,int)`、`MouseHandler.onPress(long,int,int,int)`（已用 javap 对照 1.21.7 mojmap jar 核对）

### 阶段 2：服务端化（已完成，编译通过）
- 新增 `net/ProvincePayloads.java`：自定义包（`CustomPacketPayload`）
  - `EditPayload`（C2S）：整份 NBT 压缩快照
  - `SyncPayload`（S2C）：分块同步，256KB/块，last 标记收尾
- 新增 `server/ServerConfig.java`：`config/mc_province_server.json`，`editMode` = `all`（默认）| `op`
- 新增 `server/ServerProvinceData.java`：每维度一份 store，存 `<存档>/province/<维度>.nbt`（与旧客户端单人存档路径一致，旧数据可直接沿用）
- 新增 `server/ProvinceServer.java`：进服/换维度同步、编辑校验、落盘、广播（排除发送者）
- 改 `data/ProvinceData.java`：不再读写本地文件；`receiveSync` 收分块、`tickSync` 合并提交（5 tick 冷却）
- 改 `McProvince.java`：`onInitialize` 注册 payload + 服务端事件
- 改 `client/ProvinceClient.java`：注册 S2C 接收器；tick 里驱动同步
- 改 `ProvinceEditor.java`：新增 `onRemoteSync()` 清撤销历史
- 改 `fabric.mod.json`：`environment: "*"`（双端同一个 jar）

## 安装方式

**同一个 jar 同时放进客户端和服务器**（客户端 `mods/`、服务端 `mods/` 各一份）：
- 客户端还需要：Fabric API、Xaero's World Map（1.21.7 版，必需）、Xaero's Minimap（可选）
- 服务器需要：Fabric API（Xaero 不需要）

## 测试步骤

1. 起 Fabric 1.21.7 服务端（装 jar + Fabric API），客户端也装 jar + Fabric API + Xaero 世界地图
2. A 进服 → 按 M 打开 Xaero 地图 → P 进编辑器 → 画一块区划
3. B 进服 → 应该能看到 A 画的区划；A 画完后 B 会收到同步
4. 走进区划范围 → 屏幕上方弹出地名（设置里「启用提示标题」需要打开，默认关）
5. 改 `config/mc_province_server.json` 的 `editMode` 为 `op` 可限制只有管理员能画

## 已知限制 / TODO

- [ ] 运行时未实测（本机只有编译环境，没有 1.21.7 Fabric 服务端实例）
- [ ] 同步是全量快照：数据很大（几 MB）时每条编辑都传全量，带宽高；后续可改增量
- [ ] 并发编辑：后提交的覆盖先提交的（最后写入者赢）；无冲突检测
- [ ] 客户端在无 mod 的服务器上会显示空数据（服务器必须装）
- [ ] 编辑被拒时客户端会收到服务器回发的全量数据回滚（逻辑已有，未实测）

## 构建命令

```powershell
$env:JAVA_HOME = "E:\java21"
cd F:\Xiaowanwan\province_1217\Province
.\gradlew.bat build
# 产物：build\libs\Province-0.2.2+1.21.7.jar
```
## 追加修复（2026-11-11）
- 客户端数据层加「已同步」门闩：收到首份服务端数据之前，本地编辑不提交，防止空数据覆盖服务器
- worldMapEnabled 默认 true（新玩家进服直接能看到别人的领土）
- titleEnabled 默认 true（进入区划直接弹地名）
- 产物：build/libs/Province-0.2.2+1.21.7.jar（双端同一个 jar）
## 修复：JOIN 同步与 checkWorld 的时序竞争（2026-11-11 13:34）
现象：主机开局域网前画的 A 在客机进服后看不到；客机画 B 后 A 被覆盖，双方都只剩 B。
根因：客机 JOIN 时服务端下发的全量同步包先到，被当前 tick 的 checkWorld（检测到换世界）清空；客机随后画 B 时上传「只有 B」的快照覆盖服务端。
修复：
1. applyRemote 应用同步成功后，立刻把 worldKey 更新为当前世界键（防止 checkWorld 随后清空）。
2. checkWorld 换世界时主动发送 RequestSyncPayload 向服务端要全量数据（兜底 JOIN 包时序错位/丢失）。
3. 服务端新增 RequestSyncPayload 接收器，收到即 syncTo 该玩家。
4. 保留「已同步」门闩：首份数据到达前客户端不上传。
## 追加：归属权限（2026-11-12）
- Province 数据新增 owner 字段（创建者 UUID；旧数据为空 = 只有 OP 能改）
- 服务端编辑校验：own 模式下只能改/删自己创建的区划；OP 可改全部；新块归属由服务端按提交者登记（防伪造）
- 新增 VectorShape 同等内容比较（Province.sameContent），visible 不计入"修改"
- editMode 新默认 own；已有配置文件需手动改为 own（或删掉重新生成）
- 产物：build/libs/Province-0.2.2+1.21.7.jar
