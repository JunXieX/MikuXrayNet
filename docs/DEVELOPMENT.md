# MikuXrayNet 开发文档

> 作者：JunXieX　|　MikuMC 系列插件交流群：1105054380　|　非开源项目，请勿二次分发
>
> 面向后续维护者：本文说明**它怎么跑起来、数据怎么流动、哪些规矩不能破**。
> 使用与配置说明见 [README](../README.md)，本文只讲实现。

---

## 1. 项目定位

一个 Paper/Folia 服务端插件，做两件互不干扰的事：

| 模块 | 目标 | 代价 |
| --- | --- | --- |
| 反矿透（antixray） | 出站区块封包里，把玩家**当前看不见**的目标方块换成伪装方块；玩家真能看见时再换回真方块 | 每区块一次解码 + 判定 + 重编码（工作线程），可落盘复用 |
| 带宽优化（bandwidth） | 不改变客户端所见，减少封包数量与体积（零位移包、方块变更合并、实体剔除、AFK 降级、高延迟降视距） | 少量判定与定时复检 |

两条链路共享同一套「遮挡位图」（第 7 节）与同一套线程纪律（第 4 节），但装配、停机、配置互相独立：
任一模块装配失败只停用它自己。

**外部依赖**

| 依赖 | 关系 | 用途 |
| --- | --- | --- |
| PacketEvents | 硬前置 | **仅启动期**读取方块状态映射，构建 {@code BlockStateRegistry}（空气/流体/遮挡/伪装权重）；运行期零引用 |
| ProtocolLib | 软前置（缺它则反矿透停用） | 唯一的封包拦截通道：异步监听 + 每玩家有序发送队列 |
| zstd-jni | 服务端自带 | 磁盘缓存压缩；不可用时回退 JDK Deflater |

---

## 2. 目录与包职责

```
src/main/java/net/mikumc/mikuxraynet/
├── MikuXrayNet.java              插件主类：生命周期、reload 编排、world/chunk 卸载监听、带宽装配
├── AntiXrayRuntime.java          反矿透装配与停机（持有反矿透侧全部组件）
├── CommandRegistrar.java         Brigadier 命令注册；命令名/别名/权限常量（与 paper-plugin.yml 对照守门）
├── command/MikuCommand.java      /mikuxraynet status|dump|probe|reload 的实现与补全
├── antixray/                     封包链路与显形
│   ├── ProtocolLibAsyncListener  区块包拦截：建任务、挂延迟、入池
│   ├── ChunkPacketAccessor       封包 ↔ 原始 section 字节 + 方块实体列表（字段级反射，不碰构造器）
│   ├── ObfuscationProcessor      解码 → 逐方块判定 → 伪装 → 重编码；产出遮挡数据
│   ├── ObfuscatedChunkIndex      伪装坐标索引（按区块共享）
│   ├── RewriteCache              改写结果内存缓存（指纹键）
│   ├── NeighborChunkProvider     区块边界邻块快照（仅 mode≠all 时需要）
│   ├── ChunkBatchGate/BatchTable 1.20.2+ 区块批量包闸门
│   ├── ProximityRevealer         邻近显形：巡检调度、批量发包、事件驱动、区块下发即显形
│   ├── ProximityScanner/Selector 候选坐标扫描与可见性判定（视锥 + 射线）
│   ├── RevealedSet               按玩家的已显形集合（含过期与容量安全阀）
│   ├── BlockChangeRevealListener 出站方块变更 → 注销伪装坐标 / 事件驱动显形 / 变更日志
│   ├── LeakFilterListener        方块实体/世界事件/音效/粒子/爆炸的二次泄露过滤
│   ├── ChunkChangeJournal        区块包在途期间的方块变更环形日志（放行后补发）
│   └── RevealProbe               /mxnet probe 的只读诊断
├── occlusion/                    遮挡位图数据层
│   ├── OcclusionStores           按世界分域；世界卸载立墓碑
│   ├── OcclusionStore            单世界：section 键 → 遮挡描述（SWMR）
│   ├── BitmapOcclusion           4096 格位图（64 long，opaque VarHandle）
│   ├── OcclusionEngine           射线遍历（实体剔除与显形共用）
│   ├── OcclusionPayload          整列遮挡数据的紧凑编码（随缓存落盘）
│   └── SwmrLongMap               单写多读原始 long 映射
├── codec/                        区块字节编解码（不依赖 NMS 协议类）
│   ├── ChunkSection              调色板/位宽解码与重编码；伪装替换
│   ├── Chunk/ChunkCodec          区块整体结构
│   ├── 各类 Palette              单值/间接/直接调色板
│   └── ChunkScratch              ThreadLocal 复用缓冲
├── cache/                        磁盘缓存
│   ├── DiskCacheStore            条目索引、容量与过期、异步写盘队列
│   ├── RegionFile                32×32 区块一个文件，追加记录 + 定期压实
│   ├── BufferedLinearV3Format    容器格式（版本、压缩方案、记录头）
│   ├── DiskPayload               单条负载信封（字节 + 伪装坐标 + 遮挡负载 + 指纹）
│   └── ZstdSupport               压缩后端探测 / 自动下载 / 回退
├── bandwidth/                    带宽优化
│   ├── ThrottlePipeline          装配与停机；各子模块开关
│   ├── EntityPacketFilter        零位移包抑制（朝向包一律放行）
│   ├── BlockChangeMerger         方块变更合批
│   ├── EntityCuller              实体射线/视锥剔除与复检
│   ├── AfkTracker/AfkState       挂机判定与低价值包丢弃
│   ├── LatencyMonitor            高延迟降视距
│   └── Schedulers                统一「回到实体所属线程」的调度入口
├── config/                       antixray.yml / bandwidth.yml / blacklist.yml 的解析、钳制与指纹
├── bootstrap/                    前置探测、平台判定、方块状态与遮挡规则装配
├── concurrency/                  MikuWorkPool（固定线程 + 有界队列 + 看门狗）、RewriteTask
└── util/                         Diagnostics、ReloadCoordinator、BypassRegistry、常量
```

**分层纪律**：`codec` / `occlusion` / `cache` 不引用 Bukkit 世界对象，可离线单测；
`antixray` 与 `bandwidth` 之间只有两处点对点复用（`bandwidth.Schedulers` 的实体调度入口、
`ProximitySelector#withinFrustum` 的视锥数学），其余服务一律经主类与运行时获取。

---

## 3. 生命周期与装配

### 3.1 启动

```
onLoad    → new MikuConfig(this).load()           // 只读盘，不碰世界
onEnable  → PlatformSupport.configure(...)        // 平台判定必须最先
          → reloadCoordinator.runExclusivelyOnEnable(() -> {
                runtime = new AntiXrayRuntime(...)
                runtime.startBypassRegistry()
                runtime.startAntiXray()            // 失败 → 清理并置 null，不影响带宽
                startBandwidth()                   // 失败 → 停掉半成品管线
            })
          → new CommandRegistrar(this).register()
          → startDiagnosticsTask()                 // bandwidth.yml: diagnostics.interval-seconds
```

装配段整体在**重载会话锁**内执行：否则一个正排队在锁上的 `/reload` 可能与启用装配交错，
同一次启用里出现两条带宽管线（其中一条既不在字段里也没人 stop）。

### 3.2 反矿透装配顺序（不得随意调整）

```
AntiXrayConfig.enabled?  →  否：直接返回（FINE）
DependencyGuard.isPacketEventsPresent?  →  否：WARN 并返回
PacketEventsHook.initialize(...)        →  失败返回 false
BlockStateRegistry + ChunkCodec + PaletteOptions
OcclusionStores（恒建：实体剔除与显形都依赖它）
ObfuscationProcessor.create(...)        →  !isActive()：WARN 并返回
DependencyGuard.isProtocolLibPresent?   →  否：WARN 并返回
NeighborChunkProvider（仅 neighbors.enabled）
ObfuscatedChunkIndex + RevealedSet（仅 proximity.enabled，否则 null）
DiskCacheStore（含 ZstdSupport 初始化；失败降级为纯内存）
MikuWorkPool(threads, queueCapacity)
ProtocolLibHook.register(config, processor, pool, neighbors, indexRef, revealedRef, bypass, diskCache, liveConfig)
   →  失败：关池、关缓存、清索引字段并返回
字段发布（index/revealed/pool/hook/diskCache/occlusionStores）→ warnUnsupportedWorldHeight()
startProximity(...)   →  ProximityRevealer / BlockChangeRevealListener / LeakFilterListener + chunkDeliveryHook
startOcclusionPatch(...)  →  OcclusionPatchListener（方块变更即时补位图）
```

**索引走可变引用**：`ProtocolLibAsyncListener` 不随热重载重建，因此拿到的是
`Supplier<ObfuscatedChunkIndex>` / `Supplier<RevealedSet>`，热重载把运行时字段置 null 后改写链路立刻读到 null
（关闭即零开销，且与面板一致）。这一点改动时最容易踩：不要把它换回构造期固定实例。

### 3.3 停机顺序（不得调整）

```
停诊断任务
→ 摘掉 throttlePipeline 字段并 stop()      // 偿还延迟包、恢复被隐藏实体与视距
→ 摘掉 runtime 字段并 stopForDisable():
      protocolLibHook.unregister()          // 之后不再有坐标写进索引
      stopProximity()                       // 巡检任务 + 事件监听 + 索引
      stopOcclusionPatch()
      closeDiskCache()                      // 先落盘再关句柄
      workPool.close()                      // 未开始的任务逐一放行（fail-open）
      ChunkScratch.clearThreadLocals() / ProximityRevealer.clearThreadLocals() / OcclusionPatchListener.releasePooledBuffers()
      bypassRegistry.stop()
      occlusionStores.clear()
→ WorldBlacklistConfig.publish(null)        // 进程级静态名单必须复位
```

Bukkit 在调用 `onDisable()` **之前**就把插件置为 disabled，此时任何新调度都会被拒绝
（`Schedulers` 会打一次 WARN）。因此停用期的「恢复被隐藏实体 / 还原视距」走**同步**路径，不能依赖调度。

### 3.4 热重载

```
/mikuxraynet reload
→ ReloadCoordinator.reload(Target)
     ① 记下旧 dst 指纹（antiXray.configHash / bandwidth.configHash）
     ② config.load() + PlatformSupport.configure(...)
     ③ invalidateCaches()        → 运行时失效改写缓存 / 邻块快照；磁盘缓存 flush
     ④ restartPeriodicTasks()    → runtime.restartProximity() + restartBandwidth() + startDiagnosticsTask()
     ⑤ 回显结果（含 configurationFallbackNote：解析失败时如实告知）
```

**生效范围**：只有邻近显形与带宽参数真正热生效。隐藏方块表、伪装权重、分层与遮挡规则在
`ObfuscationProcessor#create` 时冻结，线程池、缓存容量、编码器选项同理——这些键改了必须重启，
`ReloadCoordinator.RESTART_REQUIRED` 会把它们逐项列出。

**缓存指纹用的是启动期配置**（见 `ProtocolLibAsyncListener#rewriteFingerprint`）：规则冻结期间若改用
reload 后的新指纹，旧规则产物会以新指纹落盘，重启后（处理器换成新规则）命中这批旧产物，配置改动对
这些区块永久不生效。改用启动期指纹后，重启时指纹随新配置变化，旧条目自然不命中并重写。

**刻意保留**：伪装索引与已显形集合。热重载不会让服务端重发已加载区块，清空它们会让已下发的假矿
永远无法还原。`restartProximity()` 因此传 `clearIndexes=false`，失败路径同样不得清空。

**已知瞬态**：`restartBandwidth` 先 `stop()`（恢复被隐藏实体/视距）再 `start()`（按新配置重新隐藏），
热重载瞬间被剔除的实体可能出现一次极短闪烁。这是「先彻底还原再干净重建」的必然结果，明确接受。

---

## 4. 线程模型

| 线程 | 谁 | 允许做什么 |
| --- | --- | --- |
| 网络线程（Netty） | ProtocolLib 异步监听回调 | 只读封包字段、取玩家/世界**值**、建任务、登记延迟、入池。**不做解码、不碰世界内容、不阻塞** |
| 工作线程（MikuWorkPool） | `MikuXrayNet-Worker-N`（daemon） | 解码、判定、重编码、缓存读写。**禁止任何 Bukkit API** |
| 看门狗（MikuXrayNet-Watchdog） | 单线程定时 | 任务超时放行、解除在途登记 |
| 主线程 / Folia 区域线程 | Paper 主线程或区域线程 | 邻块快照抓取、显形巡检、批量发包、实体隐藏/恢复、视距调整 |
| ProtocolLib 异步发送 | 每玩家有序队列 | 真正把包写出；`signalPacketTransmission` 触发 |

**例外：同步监听**。`BlockChangeRevealListener`、`LeakFilterListener`、`BlockChangeMerger` 注册的是
**同步**（非异步）出站监听——它们必须在 netty 管线内就地决定「取消原包 / 改写顺序」，因此会在 netty
线程上直接写显形索引、遮挡位图补丁与变更日志。这几处只碰并发容器与基本类型，绝不触碰 Bukkit 世界对象。

**调度纪律**：所有 Bukkit 世界/实体访问统一走 `bandwidth.Schedulers.onEntity / repeatOnEntity`
（`Entity#getScheduler()` 在 Paper 与 Folia 上是同一套 API）。区域化线程下不能跨区域访问实体，
`EntityCuller` 用 `owns()`（`Bukkit.isOwnedByCurrentRegion`）判定；Paper 上它恒为真。

**fail-open 是硬纪律**：队列满、解析失败、处理超时、任何异常，一律放行原包。所有出口共用
`RewriteTask#signalOnce()` 保证「恰好放行一次」，重复放行会让客户端收到两次同一区块。

---

## 5. 区块改写链路（反矿透主体）

### 5.1 拦截与建任务

```
onPacketSending
├─ isCancelled / !processor.isActive() → 清理残留批次闸门后放行
├─ CHUNK_BATCH_START    → batches.open(player)          // 开闸门，不延迟
├─ CHUNK_BATCH_FINISHED → batches.remove(player) 后延迟到本批区块全部完成
└─ MAP_CHUNK            → handleChunk
```

`handleChunk` 的判定顺序（每一步失败都直接放行，且**不取闸门**）：

1. `player` 非空、世界只取一次（避免 TOCTOU）；
2. `appliesTo(player, world)`：黑名单世界 / 直通名单 / 维度关闭 / 配置开关；
3. `AntiXrayConfig.Dimension.of(environment)` + `live().enabledForWorld(...)`；
4. `workPool.hasCapacity()`（队列满 → `chunksSkippedQueueFull`，**必须计数**，否则「改写看着正常但能透视」无法归因）；
5. `new ChunkPacketAccessor(packet)`（字段级反射；失败 → 一次性中文提示 + 放行）；
6. `batches.acquire(player)`：取闸门与 `chunkStarted()` 必须是一次原子操作；
7. 取 `minHeight / sectionCount / timeoutMillis`、`changeJournal.sequence()`，构造 `RewriteTask`；
8. `marker.incrementProcessingDelay()` **先于** `scheduleTimeout`（否则看门狗可能先放行、延迟计数无人归还）；
9. 需要邻块 6 面判定且缓存未命中 → 转主线程/区域线程 `capture`，抓完再入池；否则直接 `workPool.execute`。

### 5.2 工作线程

```
handleAsync → rewriteOnWorker
├─ 内存缓存命中（sourceHash 指纹一致）→ 直接 writeBack
├─ 磁盘缓存命中（DiskPayload 解码成功）→ 回填内存缓存 → writeBack
└─ 未命中 → ObfuscationProcessor.rewrite(source bytes)
      ├─ 解码（调色板 / 位宽 / 方块实体列表）
      ├─ 逐方块判定：目标方块 && 当前不可见 → 换成伪装方块
      ├─ 产出 Result{ bytes, positions, occlusionPayload, changed }
      └─ 失败 → null（放行原包，计数）
```

改写的**可见性判定**按模式：
- `mode=all`（默认）：只要玩家看不见就藏（含洞穴暴露的矿脉）——不做 6 面判定，因此不需要邻块快照；
- `mode=enclosed`：只藏六面都被包住的方块，需要邻块贴边快照来消除区块边界漏洞。

### 5.3 回写与放行

`rewriteOnWorker` → `writeBack`：把改写后的字节写回封包、替换方块实体列表、必要时下调调色板位宽；
随后执行 `release`（在 `RewriteTask` 的闭包里）：

```
gate.chunkDone()                        // 归还批次计数
asynchronousManager.signalPacketTransmission(event)   // 真正放行
resendCellsChangedDuringRewrite(...)    // 持包期间该区块被改过的格子，补发当前状态
clearRevealedOnDelivery(...)            // 作废本接收者在该区块的已显形标记
chunkDeliveryHook.onChunkDelivered(...) // 区块下发即显形（该区块中当前可见的坐标）
```

**顺序即语义**：标记作废必须排在放行之后。若在放行前清标记，清完之后、包真正写出之前落下的一次显形
会被客户端按「区块未加载」丢弃，而标记已经写回——该坐标直到区块重发或过期前都不再显形。

### 5.4 缓存

| 层 | 键 | 失效条件 |
| --- | --- | --- |
| 内存 `RewriteCache<CachedChunk>` | 世界 + 区块 + 源字节指纹 + 配置指纹 + 邻块指纹 | 源字节变化、配置指纹变化、邻块快照变化、世界卸载、reload |
| 磁盘 `DiskCacheStore` | 区域文件内按区块槽位 + 同上一整套指纹 | 条目过期、容量上限、版本不符（整体重建）、世界卸载 |

内存缓存条目携带 `occlusion` 负载，使**命中路径**（不经解码）也能把遮挡数据恢复进 `OcclusionStore`——
否则「命中率越高、遮挡数据越缺」，实体不再被隐藏。

---

## 6. 邻近显形链路

目标：把已经下发成假方块的坐标，在玩家**真的能看见**时换回真方块。

### 6.1 三条触发路径

| 路径 | 入口 | 说明 |
| --- | --- | --- |
| 周期巡检 | `ProximityRevealer` 的实体调度任务（`proximity.interval-ticks`） | 按玩家扫描周边区块的伪装坐标，取最近的若干候选 |
| 事件驱动 | `BlockChangeRevealListener` | 玩家挖开/炸开遮挡物时立即补发（不等巡检） |
| 区块下发即显形 | `ProtocolLibHook#setChunkDeliveryHook` | 区块包放行后紧接着把该区块内当前可见的坐标显形，消掉「先伪装、等巡检才还原」的空窗 |

### 6.2 判定流程

```
候选坐标（ObfuscatedChunkIndex 按区块共享）
→ 视锥粗筛（ProximitySelector，与带宽侧共用同一套数学）
→ 遮挡射线（BitmapVisibility → OcclusionRay，逐 section 位图遍历；只对候选点打射线）
→ 命中可见 → 加入批量包（Paper 原生批量方块变更）→ 记账（ProximityStats）
```

**硬约束**：玩家看得见的方块必须是真方块，因此判定一旦给出「可见」就立即还原——宁可多显形、不因成本跳过。
**反向的例外是缺数据**：某 section 还没有遮挡数据时按「遮挡」处理（`BitmapVisibility` 用全遮挡单例兜底），
即**不显形**。缺数据意味着该段尚未被改写链路建表（超高世界、判定子系统降级），此时少显形只是观感损失，
泄露真矿则不可接受；数据随区块重发自然补齐。`RevealedSet` 记录「已发给该玩家」的坐标，避免重复发包；
容量安全阀触发时**放弃登记但仍放行显形**。

### 6.3 结构

| 结构 | 键 | 生命周期 |
| --- | --- | --- |
| `ObfuscatedChunkIndex` | 区块 → 伪装坐标集合 | 区块卸载时摘除（唯一回收路径） |
| `RevealedSet` | 区块 → 玩家 → 已显形坐标 | 玩家退出 / 区块卸载 / 过期 / 容量上限 |
| `ChunkChangeJournal` | 环形 512 条（世界 + 区块 + 坐标 + 序号） | 区块包放行时按序号比对补发 |

### 6.4 泄露过滤

`LeakFilterListener` 拦截会间接暴露隐藏坐标的包：方块实体数据、世界事件、音效、粒子、爆炸。
判定口径：坐标仍在索引中且未被该玩家显形 → 吞包；解析失败一律放行（fail-open）。

---

## 7. 遮挡位图

**为什么需要**：本插件不做原生命中射线（那要读世界，代价高且受线程限制），而是从**已解码的区块字节**
旁路生成「哪些格子挡视线」的位图，之后实体剔除与显形判定都只查这份位图。

```
OcclusionStores           按世界分域（同名区块在不同世界不能互相覆盖）
  └─ OcclusionStore       单世界：SectionKey(long) → SectionOcclusion
        ├─ SolidOcclusion   整段全遮挡
        ├─ EmptyOcclusion   已知全不遮挡（与「表里没有」区分，避免稀释覆盖率）
        └─ BitmapOcclusion  4096 位（64 long，opaque VarHandle 读写）
```

- **写入**：反矿透解码时（`ObfuscationProcessor#captureOcclusion`）、磁盘/内存缓存命中时（恢复负载）、
  方块变更时（`OcclusionPatchListener` 单格补丁）。
- **读取**：`OcclusionEngine` 射线（异步线程）、`BitmapVisibility` 显形判定。
- **回收**：区块卸载摘整列、世界卸载摘整表并立墓碑（`WorldUnloadEvent` 用 MONITOR + ignoreCancelled，
  被取消的卸载不立墓碑；`WorldLoadEvent` 解墓碑）。

**section 高度只有 8 位**（Y ∈ -2048..2047）：超高/超低世界不建表，启动期一次性告警。

---

## 8. 带宽优化

`ThrottlePipeline` 按配置逐个子模块注册，任一失败只停用它自己：

| 子模块 | 触发 | 做法 | 关键纪律 |
| --- | --- | --- | --- |
| `EntityPacketFilter` | 出站实体包 | 纯位移且全 0 的 `REL_ENTITY_MOVE` 直接不发 | **朝向包一律放行**：全 0 是合法朝向，取消会导致朝向不同步 |
| `BlockChangeMerger` | `BLOCK_CHANGE` | 短窗口内合并成一条批量包 | 挖掘/放置半径内原包立即放行（不影响手感） |
| `EntityCuller` | 周期复检 + 追踪事件 | 被墙完全挡住的实体 `hideEntity`，条件恢复时 `showEntity` | 玩家/自身/烟花/强制可见距离内始终放行；静止实体跳过高频复检 |
| `AfkTracker` | 周期 | 挂机玩家按距离丢弃粒子与方块破坏动画 | 悬挂恢复即退出 AFK |
| `LatencyMonitor` | 周期 | 延迟持续超阈值 → 降视距 | 恢复后还原；停用期同步还原 |

**线程纪律**：带宽侧不直接开线程。判定与实体操作都在玩家所属线程（`Schedulers.onEntity`），
因此 `hideEntity` / `setViewDistance` 永不跨线程；唯一例外是遮挡射线——`EntityCuller` 把请求投给
`OcclusionEngine` 的专用池（纯内存步进，不碰 Bukkit），结果再经 `Schedulers.onEntity` 回投到实体所属线程，
按当拍位置复判后才下 `hideEntity`（避免用陈旧几何去隐藏实体）。

---

## 9. 配置系统

三份文件 + 三个记录层次：

```
MikuConfig                      // 入口：持有三份配置 + fallbackNote
├─ AntiXrayConfig               // antixray.yml
│    ├─ Dimension (overworld / nether / the_end) → EffectiveObfuscation(hideBlocks, replacementWeights, mode)
│    ├─ Neighbors / Occlusion / Proximity / InstantReveal / ViewChange / DiskCache / ReplacementBand
│    └─ WorldOverride(pattern, glob, ...)        // 逐世界覆盖（黑名单优先级更高）
├─ BandwidthConfig              // bandwidth.yml
│    ├─ EntityPackets / BlockChanges / Palette / EntityCulling / Afk / Latency / Diagnostics
└─ WorldBlacklistConfig         // blacklist.yml（进程级静态名单，publish 一次 volatile 写）
```

**解析纪律**

1. 外部输入（yml 文本、玩家输入）只在**解析入口**校验与钳制一次，随后以不可变 record 传递，内部不重复检查；
2. 关键项钳制到安全区间并**提示一次**（下限防「看得见假方块/被剔掉的实体」，上限防「明显延迟」）；
3. `configHash` 汇总「会改变改写结果」的字段，作为内存/磁盘缓存指纹的一部分；
4. 解析失败保留旧配置并记进 `fallbackNote`，reload 回显里如实告知。

**改配置会让缓存失效重建**：隐藏清单、伪装方块、遮挡规则、`remove-block-entities`、
`bandwidth.palette` 段、`disk-cache.zstd-sha256` 等。
**世界黑名单不参与指纹**：命中世界直接跳过改写、不产生缓存条目；移出名单后旧条目由内容指纹判定是否仍有效。

---

## 10. 持久化格式

### 10.1 磁盘缓存

```
cache/
├─ lib/                     zstd 本地库（自动下载时）
└─ r.<regionX>.<regionZ>.mxrc        每个文件 32×32 区块
      [文件头: 魔数 + 版本 + 压缩方案]
      [记录 1][记录 2]...
        记录 = [长度][XXHash32][压缩负载]
        负载 = DiskPayload.encode(...)
```

- 一个区块一条独立记录：改 1 个区块只重压 1 条，不重压整桶；读取时整条物化（一槽一条，无需中间表示）；
- `DiskPayload` 信封：`[i64 源字节指纹][i32 坐标数][i32×N 坐标][i32 遮挡负载长度][遮挡负载][改写后字节]`；
- 无伪装坐标的条目**不存区块字节**（命中后也无需写回），显著缩小占用；
- 版本升级或格式变更 → 旧文件整体删除重建（属正常现象）。

### 10.2 遮挡负载

```
[i32 sectionCount]
[u8 × N  标签: 0=全不遮挡 1=整段遮挡 2=逐格位图]
[i64 × 64 × (标签为 2 的段数)]          逐格位图
```

三态标签让实心/空气段零字节开销。

### 10.3 坐标与键

| 编码 | 用途 | 布局 |
| --- | --- | --- |
| `SectionKey` | 遮挡表键 | `[63..36] chunkX 28 位 | [35..28] sectionY 8 位 | [27..0] chunkZ 28 位` |
| `OcclusionPayload` 内位下标 | 段内坐标 | `y<<8 | z<<4 | x` |
| `DiskPayload` 坐标 | 伪装坐标清单 | 区块内相对编码的 `int` |

---

## 11. 关键不变量（改动前必读）

1. **恰好放行一次**：每个被延迟的封包最终由 `RewriteTask#signalOnce()` 放行且只放行一次。
   新增出口必须复用它，不能直接 `signalPacketTransmission`。
2. **网络线程零计算**：回调里只允许读封包字段、取玩家/世界的**值**、建对象、入池。解码/判定/重编码在工作线程。
3. **工作线程零 Bukkit**：工作线程内不得调用任何 Bukkit API，需要世界数据必须提前在网络或区域线程取值。
4. **看得见必为真**：判定为可见就立即还原；**缺遮挡数据按「遮挡」处理（不显形）**，只有整个判定子系统
   缺失/异常时才按可见放行。方向相反的两条不可混用（见 `BitmapVisibility` 与 `泄漏过滤`）。
5. **fail-open**：任何超时/积压/异常都放行原包，绝不卡玩家加载。
6. **索引可变引用**：改写链路通过 `Supplier` 读索引/已显形集合，关闭邻近显形即读到 null。
7. **reload 不清共享索引**：`stopProximity(false)`，失败路径同样如此。
8. **停机顺序**：反矿透「注销拦截 → 停显形 → 关缓存 → 关池 → 停直通」，带宽先于反矿透。
9. **世界分域**：遮挡表、缓存、磁盘句柄都按世界名分域；世界卸载必须同时摘除三处并立墓碑。
10. **不跨线程持有对象**：缓存键只含世界名与基本类型，绝不持有 `World`/`Chunk`/`Player`/`PacketEvent`。

---

## 12. 测试

```
mvn -q test                        # 全量（约 115 个测试类）
mvn -q test -Dtest=ChunkSectionPaletteFilterTest
```

- 单测尽量用**真实实现**：`ChunkSection` / `OcclusionStore` / `RegionFile` 等都直接构造真实对象，
  不复制业务逻辑；
- 无法构造的 Bukkit 类型用最小桩（`bench/StubRegistry`、测试内的 `RecordingSink` 等）；
- 性能相关结论用 `bench/*BenchmarkTest`（同进程对比不同实现路径，输出相对倍数，不做绝对时间断言）；
- 真机样本回归：`tools/RealChunkPayloadRegressionTest` 用 `src/test/resources/real-chunk-payload.bin` 验证真实封包可解码可改写。

**构建期守门**：`PluginDescriptionConsistencyTest` 校验 `paper-plugin.yml` 与代码常量（命令名/别名/权限/版本）、
`BuildPipelineTest` 校验构建脚本关键项。

---

## 13. 构建与发布

```
mvn -q -DskipTests package         # 产出 target/MikuXrayNet-<version>.jar
```

- 版本唯一来源：`pom.xml` 的 `<version>`；`paper-plugin.yml` 用 `${project.version}` 过滤替换；
- 编译目标 Java 25，`release=25`；编译强制 `fork=true`（规避 JDK 25 上进程内编译的 javac 缺陷）；
- `.class` 只保留行号与源文件名（`-g:lines,source`），jar 不写 Maven 元数据；
- CI：`.github/workflows/build.yml` 构建 + 上传产物；发版按 `v<version>` 打 tag。

---

## 14. 常见修改指引

| 想改什么 | 落点 | 注意 |
| --- | --- | --- |
| 新增/调整默认隐藏方块 | `antixray.yml` + `AntiXrayConfig` 的维度段解析 | `tag(...)` 写法由 `TagExpansion` 展开 |
| 新增一种伪装权重策略 | `ObfuscationProcessor` 的伪装选取 + `ReplacementBand` | 权重表在启动期固化为数组，热重载不生效 |
| 改判定可见性的数学 | `ProximitySelector`（视锥，显形与剔除共用）、`OcclusionRay`（射线，显形与剔除共用） | 视锥与射线各只有一份实现；改它要同时跑显形侧（`*Visibility*Test`、`Proximity*Test`）与剔除侧（`EntityCuller*Test`） |
| 新增带宽子模块 | `bandwidth/` + `ThrottlePipeline` 装配 + `BandwidthConfig` | 必须提供配置开关与停机恢复路径 |
| 新增磁盘缓存字段 | `DiskPayload` + 升 `BufferedLinearV3Format.VERSION` | 旧缓存会整体重建 |
| 改命令/权限 | `CommandRegistrar` 常量 + `MikuCommand` + `paper-plugin.yml` | 三处必须逐字一致，否则 `PluginDescriptionConsistencyTest` 失败 |

---

## 15. 已知边界

| 边界 | 现状 | 影响 |
| --- | --- | --- |
| 服务端 NMS 私有字段改名 | `ChunkPacketAccessor` 字段级反射定位失败 | fail-open 放行原包，启动日志与 `status` 显示「字段级取数不可用」，不崩服 |
| 世界高度超出 section 8 位范围 | 不建遮挡表，启动期告警一次 | 该世界显形/实体剔除退化 |
| 跨区域实体（Folia） | `owns()` 为假的实体本轮跳过 | 少隐藏，安全方向 |
| 玩家跨世界后的隐藏账本 | 条目保留、本周期跳过判定，回到该世界后恢复 | 期间 `hiddenCount` 偏高 |
| 磁盘目录被另一实例占用 | 自动停用被占用文件的磁盘缓存并提示一次 | 退化为纯内存缓存 |
| `/reload` 期间的在途区块包 | 放行原包 | 少量区块短暂未伪装，下一轮巡检/重发自愈 |
| 玩家运行期获得 `mikuxraynet.bypass` | 进入服务器时判定一次 | 需重进才生效（README 已写明） |
