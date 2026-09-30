# 收纳助手（Shouna）架构规划 · P0 剩余

> 版本：**v1.0** ｜ 上游：[docs/PRD.md](PRD.md) ｜ 前置：[docs/ARCHITECTURE.md](ARCHITECTURE.md)（F1，已交付） ｜ 路径基准：工程根 `shouna/`
> 本页**只描述 P0 剩余 10 条需求**的实现。P1（14 条）、P2、V2 **一律不在本页范围**——不规划、不预设方案、不预留代码路径。

**一句话结论**：本次补上「**数据不丢 + 位置树 + 状态与可信度 + 拼音检索**」——F1 的内存源换成 **Room（5 张表）**，位置从「1 条哨兵」变成真实多级树，物品可标「不在了」，搜索支持拼音。

**与 F1 的关系（读前必看）**：F1 交付「记一件 → 搜到它 → 确认还在」（内存态、位置恒哨兵）。本页在**同一套分层与同一批仓库接口**上替换实现并补齐：`ItemRepository` / `CategoryRepository` 的**接口形态不变**（F1 已按「可整体替换」定义），**只换 Impl**；新增 `LocationRepository`。F1 §7.1 登记的 4 项接缝（持久化 / 位置层级 / 状态切换 / 拼音）由本页兑现；别名、数量、Tag 三类接缝**仍置空**。

## 0. 关键决策

| 决策点 | 结论 |
|---|---|
| 范围 | P0 剩余 **10 条**：FR-01 / 02 / 03 / 05 / 11 / 20 / 23 / 25 / 27 / 41 |
| 存储 | Room 2.8.4；**5 张表**：`item` / `location` / `category` / `recent_search` / `app_config`；`version = 1`、`exportSchema = true` |
| 不建的表 | `placement`（一物一处已裁定取消）、`item_alias`（属 F2）、`tag` / `item_tag`（P2）、`recent_location`（由 `location.last_used_at` 替代） |
| 位置树 | `location.parent_id` 自引用，任意层级；**不物化 path**（递归统计属 P1） |
| 面包屑（FR-02） | **派生**：内存父链拼名称路径，不入库；长按走系统剪贴板（零权限） |
| 删除位置（FR-05） | 以**子树**为单位，二选一：**迁移到指定位置** / **物品标记 `gone` 保留**；**禁止孤儿** |
| 状态（FR-25） | 本次交付 **F1 档两动作**（`✓ 还在` / `✕ 不在了`）+ `gone` **可恢复**；「待归位」与去向备注留 P1 |
| 可信度（FR-27） | 详情页与列表行展示「最后确认」+「最后变动」；超期（默认 6 个月）加显著标记 |
| 拼音（FR-20） | 物品名：**持久化** `pinyin_full` / `pinyin_initial`；位置名 / 分类名：**构建时实时拼音化**，不落库（守 PRD 08 §7.6 的 A-2 约束） |
| 最近位置（FR-11 / 23） | `location.last_used_at` 排序取 5，**不建表** |
| 最近搜索（FR-23） | `recent_search` 小表，上限 20 条，仅**搜索页空态**展示 |
| F2 入口 | 首页「更多」折叠区，**不做阈值门控**（预置 6 个位置 → 「≥3」恒真，门控是死代码） |
| **位置必填** | **所有物品必须有位置**：`canSave` = 「名称非空 ∧ 位置已选」；未选位置**不可保存**（用户 2026-09-29 明确要求，属需求变更）。**PRD 侧状态**：非 FR 行已就地同步（登记见 `prd/05` §4.11）；FR 行受 §4.9 / §4.10 冻结条款保护、**待授权** |
| 位置选择器 | 录入页**底部弹层**内嵌同一棵树组件 + **「＋ 新建位置」**（兑现「或输入位置」）；默认**预选最近使用**的位置；不新增路由变体、无跨页结果回传 |
| 导航 | 4 条 → **5 条**（新增 `LocationBrowse(locationId: String? = null)`） |
| 界面约束 | **永久浅色 + `fontScale = 1f` 继承 F1 不改**；避让口径已变（2026-09-29 用户要求）：改为 `systemBars ∪ displayCutout ∪ ime`，键盘弹起时录入页「保存并继续」浮在键盘上方，**IME 完成键只收键盘、不代替保存**（保存唯一入口 = 按钮）。规则本体见 `docs/实现约束.md` §1（约束 1-5 / 1-6） |
| 任务 | **4 个**（P0-01 ~ P0-04），强线性 |

> **位置必填的三条落地规则**（本次新增，贯穿 §1 / §3 / §4 / §9）：
> ① **兜底位不外露**：种子里的哨兵 `未指定位置` 标记 `is_built_in = 1`，**不出现在选择器与浏览页**，用户永远选不到它；它只为「删位置档 2 的 `gone` 物品收容」与极端兜底存在。
> ② **默认值来自使用史**：位置条预选 `location.last_used_at` 最近的位置；**首次安装无使用史 → 空**，此时保存被拦下，用户必须先选或新建（即「必须手动选择」）。
> ③ **「保存并继续」保留上次位置**：连续录入沿用同一位置，不打断（守住 PRD US-02 的「不打断」，同时满足「每条都有真实位置」）。

## 1. 范围与需求映射

| 编号 | 需求 | 本页落地形态 | 落点页面 |
|---|---|---|---|
| FR-41 | 本地持久化 | Room 5 表 + 种子；强杀进程重开数据完整 | — |
| FR-01 | 多级位置树 | 位置增删改、任意层级；展示该位置**直属**物品与子位置 | P-BROWSE |
| FR-02 | 位置路径面包屑 | 详情页 / 结果行 / 浏览页均显示「家 › 储物间 › 纸箱-07」，长按复制 | 3 页 |
| FR-03 | 位置重命名 / 备注 | 改名 + 备注；改名后所有路径展示自动跟随（**不动物品记录**） | P-BROWSE |
| FR-05 | 位置删除保护 | 子树为单位 + 二选一对话框 + 孤儿防线 | P-BROWSE |
| FR-11 | 最近使用位置 | 录入页位置条**预选**最近 1 个 + 弹层内「最近」chips 5 个；同时是 FR-23「热门位置」的同一数据源 | P-ADD |
| FR-20 | 拼音首字母与全拼 | `dfs` / `dianfengshan` 命中「电风扇」；离线、零网络 | P-SEARCH |
| FR-23 | 最近搜索与热门位置 | 最近搜索词（搜索页空态）；「热门位置」= FR-11 同一数据源 | P-SEARCH / P-ADD |
| FR-25 | 物品状态 | 两动作 + `gone` 恢复；`gone` 默认从常规搜索隐藏（F1 过滤已就位） | P-ITEM-DETAIL |
| FR-27 | 位置可信度视图 | 「最后确认」+「最后变动」+ 超期标记 | P-ITEM-DETAIL / 列表行 |

> **位置必填对既有需求的影响（本次新增，必须一起看）**
> - **FR-09（F1 已交付，口径被本次改写）**：仍「只填名称即可保存」，但**位置不能再留空**。F1 的「唯一必填 = 名称」提升为「名称 + 位置」两个必填项。
> - **FR-13 草稿箱 / 待补详情已废弃**（用户 2026-09-29 裁示）：位置必填后不存在「位置待补」的记录，判定依托消失 → PRD 侧 FR-13 行已改为「删除」，`prd/08` §7.1.1 判定节与字段行、`prd/09` C-7、`prd/10` 约束 A-4 一并撤除。本页**不实现**该项目。
> - **FR-05 位置删除的「标记 `gone`」档需要一个去处**：`item.location_id` 是 `NOT NULL`，被标记的物品仍须归属某位置 → 该档的落点是**内置哨兵**（用户不可见），见 §3.3。

> **本次不做**：P1 全部 14 条（FR-04 / 06 / 13 / 14 / 21 / 22 / 28 / 29 / 33 / 38 / 42 / 43 / 44 / 47）、P2、V2；FR-09 / 10 / 12 / 19 / 26 已由 F1 交付，不重复。

## 2. 分层与单向数据流

沿用 F1 的六层与约定，只有一处替换：

| 层 | 本次变化 |
|---|---|
| 数据源 | `data/memory/InMemoryStore`（**删**）→ `data/local`（Room：`entity` / `dao` / `ShounaDatabase` / `SeedCallback`） |
| Repository | `ItemRepository` / `CategoryRepository` 接口不变、**只换 Impl**；**新增 `LocationRepository`** |
| domain | 纯 Kotlin，不依赖 Android；新增 1 个派生模型（位置树行 / 面包屑段） |
| UI | `XxxRoute` 取 VM 下传 UiState 的约定不变；新增 1 页 + 4 组件 |

**Repository（3 个）**

- `ItemRepository`：F1 的 5 个方法 + `setStatus` / `restore` + 最近搜索词（`observeRecentQueries` / `recordRecentQuery`，承载 `recent_search` 表）；`createItemQuick(name, categoryId, note, locationId)` 补第 4 个实参
- `LocationRepository`：`observeTree` / `observeChildren` / `observeItemsIn` / `create` / `rename` / `setNote` / `delete(mode, migrateTargetId)` / `touchLastUsed`
- `CategoryRepository`：只读 `observeCategories`（形态不变）

**写路径守卫**：所有跨表写（删子树 / 迁移 / 改位置 / 改状态）在**单个 Room 事务**内完成；读路径走 `Flow`。F1 的 `InMemoryStore` 及其 `Mutex` 手工串行化随内存源一并删除。

## 3. 数据模型（Room）

### 3.1 表清单（5）

| # | 表 | 要点 |
|---|---|---|
| 1 | `item` | 物品；一物一处（`location_id` NOT NULL，FK `ON DELETE RESTRICT`） |
| 2 | `location` | 位置树：`parent_id` 自引用 FK RESTRICT、`is_built_in`、`is_temporary`、`note`、`sort_order`、`last_used_at` |
| 3 | `category` | 分类；8 条内置（`is_built_in`） |
| 4 | `recent_search` | `query` / `normalized_query`（UNIQUE）/ `searched_at` |
| 5 | `app_config` | 键值；本次只写 `threshold_months = 6` |

**为什么不建**：`placement`（一物一处裁定已取消关系表）；`item_alias`（别名属 F2 的 P-ITEM-EDIT，`alias_blob` 继续空串）；`tag` / `item_tag`（FR-45 顺延）；`recent_location`（`location.last_used_at` 已足够——少一张表、少一处同步）。

### 3.2 `item` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | UUID |
| `name` | TEXT | 允许同名；身份以 `id` 判定，绝不靠名称 |
| `normalized_name` | TEXT | 归一化名称（F1 已有） |
| `pinyin_full` / `pinyin_initial` | TEXT | **新增**（FR-20）；由名称派生（A-2 允许持久化） |
| `alias_blob` | TEXT | **仍恒空串**（F2 才接别名） |
| `location_id` | TEXT NOT NULL | FK → `location.id`，`ON DELETE RESTRICT`（**孤儿防线**） |
| `category_id` | TEXT? | FK → `category.id`，`ON DELETE SET NULL` |
| `status` | TEXT | `in_storage` / `to_be_put_back` / `gone`（F1 已定的稳定 code） |
| `quantity` | INTEGER | 恒 1（F2 才接） |
| `note` | TEXT? | 可选 |
| `created_at` / `last_modified_at` | INTEGER | epoch millis |
| `last_confirmed_at` | INTEGER? | 可空；**新建即写 = `created_at` 同值**（见 §3.5 的 2026-09-30 修订）。列保持可空只为承接迁移前建的老数据 |

索引：`location_id`、`category_id`、`normalized_name`。

### 3.3 种子（首启一次性）

用 `RoomDatabase.Callback.onCreate()` 插入，**不引入初始化迁移**：

| 内容 | 值 |
|---|---|
| 哨兵位置（**1 条，不外露**） | `未指定位置`（根级，`is_built_in = true` → **选择器与浏览页均过滤掉**）。位置必填口径下它**不是用户可选项**，只为「删位置档 2 的 `gone` 物品收容」与极端兜底存在 |
| 默认位置树（6 节点） | `家`（根）→ `卧室` / `客厅` / `厨房` / `储物间` / `阳台`。**`is_built_in = false` → 与用户自建位置同权，可改名、可删除**（删光后仍可「＋ 新建位置」，不构成「无法录入」的死锁） |
| 分类 | 8 条（沿用 F1 `BuiltInData.BUILT_IN_CATEGORIES`） |
| 配置 | `threshold_months = 6` |

> **F1 遗留 D-3「哨兵父级未定」就此定案**：哨兵为**根级节点**，不挂在 `家` 之下——否则「未指定位置」会出现在 `家` 的路径里，把「不知道在哪」伪装成「在家」。
> `BuiltInData` 保留为「内置常量唯一来源」，由 `SeedCallback` 引用，字面量不散落；并**扩充** `DEFAULT_LOCATION_TREE`（默认位置树常量）。
> **位置必填的三条配套**：① 哨兵 `is_built_in = 1` 且被 UI 过滤 → 用户选不到；② 录入页位置条预选 `last_used_at` 最近者、首次为空 → 保存被拦；③ 删位置档 2 的 `gone` 物品收容到哨兵，保证 `location_id` 非空。

### 3.4 不变量（DB 外表达不了者，由代码 + 单测守）

1. 不存在指向已删位置的物品（FK `RESTRICT` 兜底 + Repository 显式自底向上删除）。
2. **位置必填**：任何物品的 `location_id` 都指向一个**真实存在**的行，且**正常录入路径永不落到内置哨兵**（哨兵仅由「删位置档 2」写入）。UI 层 `canSave` 需要位置非空。
3. 内置位置（**仅 `未指定位置` 1 条**）不可删、不可被选、不可移入自身子树；内置分类不可删。
4. 删除位置前必须先处理其子树物品（迁移或标记），**不允许隐式级联删除物品**。
5. `status = gone` 的物品不参与常规搜索（F1 的 `isActive` 过滤 + `SearchIndex` 二道过滤继续有效）。
6. 「同级不重名」是**应用层软校验**（提示但不阻断），**不得**写成 DB 约束或架构保证（SQLite 唯一索引对根级的 `NULL` 父级不判重，反会给人「已兜底」的错觉）。

### 3.5 时间戳矩阵（F1 的表 + 本次新增 3 行）

| 操作 | `last_modified_at` | `last_confirmed_at` |
|---|---|---|
| 新建物品 | = now | **= now**（与 `created_at` 同一次 now） |
| 点「还在」 | 不动 | = now |
| 改名 / 改分类 / 改备注 | 不动 | 不动 |
| **标记「不在了」** | **= now** | 不动 |
| **`gone` 恢复为 `in_storage`** | **= now** | 不动 |
| **移动到其它位置** | **= now** | 不动 |

超期判定（FR-27，详情页与列表行共用一套）：`last_confirmed_at IS NULL OR last_confirmed_at < now - threshold_months 个月`。

> **2026-09-30 修订**：「新建物品」行由「`last_confirmed_at` 保持 NULL」改为「= now」。录入本身就是一次
> 「我知道它在这儿」的确认；留 NULL 会让刚存进去的物品立刻被判超期（`last_confirmed_at IS NULL` 恒真），
> 既在列表带 ⚠，又直接进 C-4「超期未确认」清单 —— 把「记得住」变成噪声。
> **不留开关、不保留旧口径**；`last_confirmed_at` 列仍可空，以承接迁移前建的老数据（它们继续按「从未确认」参与判定）。

## 4. 任务分解

> 4 个任务、强线性依赖；配置改动全部集中在 P0-01。

### P0-01 持久化换血：Room 5 表 + 种子 + 仓库替换（无依赖）

- **新建 14**：`data/local/entity/{ItemEntity, LocationEntity, CategoryEntity, RecentSearchEntity, AppConfigEntity}.kt`、`data/local/dao/{ItemDao, LocationDao, CategoryDao, RecentSearchDao, ConfigDao}.kt`、`data/local/Converters.kt`、`data/local/ShounaDatabase.kt`、`data/local/SeedCallback.kt`、`di/DatabaseModule.kt`
- **修改 4**：`gradle/libs.versions.toml`、`app/build.gradle.kts`（Room 三件套 + KSP 参数 `room.schemaLocation` / `room.generateKotlin`）、`data/repository/ItemRepositoryImpl.kt`、`data/repository/CategoryRepositoryImpl.kt`
- **删除 1**：`data/memory/InMemoryStore.kt`
- **子步骤** ① 依赖与 KSP 参数；② 5 个 Entity（FK / 索引 / 约束取向照 §3.4）；③ 5 个 DAO（读用 `Flow`，跨表写用 `@Transaction`）；④ `Converters`（`ItemStatus ↔ code`、时间一律 `Long`）；⑤ `ShounaDatabase(version = 1, exportSchema = true)` + `SeedCallback`（§3.3）；⑥ 两个 Impl 改为 DAO 实现；⑦ 单测：转换器、时间戳矩阵；androidTest：种子完整性、schema 导出
- **验收** `:app:assembleDebug` + `:app:testDebugUnitTest` 成功；**强杀进程后重开数据完整**（FR-41）；`app/schemas/…/1.json` 生成；APK 体积相对 F1 基线（13,385,039 B）的增量记录在案
- **PRD 承接** FR-41、FR-25（状态落库）、NFR-01 / 02 / 17（零网络、零新增权限）

### P0-02 位置树与路径（依赖 P0-01）

- **新建 8**：`data/repository/LocationRepository.kt` + `LocationRepositoryImpl.kt`、`util/LocationPath.kt`、`ui/screen/location/LocationBrowseScreen.kt` + `LocationBrowseViewModel.kt`、`ui/component/{LocationTreeItem, LocationBreadcrumb, LocationPickerSheet}.kt`
- **修改 7**：`di/RepositoryModule.kt`、`ui/navigation/ShounaRoute.kt`（+1 键）、`ui/navigation/RouteHandler.kt`（+1 composable + `goLocationBrowse`）、`ui/screen/add/QuickAddScreen.kt` + `QuickAddViewModel.kt`、`ui/screen/home/HomeScreen.kt`、`ui/screen/itemdetail/ItemDetailScreen.kt`
- **子步骤** ① `LocationRepository`：树读取、增删改、**删除两条路径**（迁移 / 标记 `gone` 并收容到哨兵）、`touchLastUsed`；② `LocationPath` 由父链拼名称路径（纯函数，可单测）；③ `P-BROWSE` 页：树（**过滤 `is_built_in`**）、该位置直属物品、增删改、删除对话框（含影响范围提示）；④ 面包屑组件 + 长按复制；⑤ 录入页位置条（**必填**：未选即禁用保存；默认预选最近使用位置）+ `LocationPickerSheet`（树 + 最近 chips + **「＋ 新建位置」**，选中即回填）；⑥ 首页「更多」折叠区（本次仅 1 项入口）
- **验收** 单测全绿（路径拼装、删除两路径无孤儿、内置不可删、重名仅提示）；删位置后 `item.location_id` 全部有对应行；改名后详情页路径即时跟随；**未选位置时保存按钮禁用、选中/新建后恢复**；连续录入 5 件仍 ≤ 40 秒且位置一致
- **PRD 承接** FR-01 / 02 / 03 / 05 / 11、FR-23（热门位置）、NFR-07

### P0-03 状态与可信度（依赖 P0-02）

- **新建 1**：`ui/component/OverdueMark.kt`
- **修改 5**（`ItemRepositoryImpl` 已在 P0-01 计入一次）：`data/repository/ItemRepository.kt` + `Impl`（`setStatus` / `restore`）、`ui/screen/itemdetail/ItemDetailViewModel.kt`、`ui/component/ItemRow.kt`、`util/TimeUtil.kt`
- **子步骤** ① 两动作 + `gone` 恢复（写 `status` 与 `last_modified_at`）；② 详情页「最后确认」+「最后变动」两行 + 超期标记；③ 列表行与搜索结果行补时间 + ⚠；④ 单测：状态矩阵（两动作 / 恢复 / 超期边界含「从未确认」）
- **验收** 单测全绿；点「不在了」后该物品从搜索结果消失、详情页可恢复；超期标记在 6 个月边界两侧正确
- **PRD 承接** FR-25（F1 档）、FR-27、NFR-08

### P0-04 拼音检索与搜索深化（依赖 P0-03）

- **新建 2**：`util/PinyinUtil.kt`、`ui/component/RecentQueryChips.kt`
- **修改 7**（`ItemRepositoryImpl` 已在 P0-01 计入一次）：`domain/search/{SearchDoc, SearchIndex, SearchScorer, MatchType}.kt`、`data/repository/ItemRepositoryImpl.kt`、`ui/screen/search/SearchScreen.kt` + `SearchViewModel.kt`
- **子步骤** ① `PinyinUtil`：全拼 + 首字母；多音字取默认读音、非汉字原样保留、不做声调与谐音纠错；② `SearchDoc` 补 `pinyinFull` / `pinyinInitial` / `locationPath` / `lastConfirmedAt`；③ 索引构建时对位置名、分类名**实时拼音化并按名称缓存**（不落库，守 A-2）；④ `MatchType` 增拼音档，权重按「名称前缀 > 名称子串 > 拼音 / 别名 > 位置 / 分类」；⑤ 搜索页空态显示最近搜索词（**执行搜索且结果非空**时 upsert，上限 20 条、按归一化去重）；⑥ 结果行补路径与时间
- **验收** `dfs` / `dianfengshan` / `风扇` 均命中「电风扇」；1000 件 ≤ 300ms、5000 件 ≤ 800ms（JVM 微基准）；最近搜索的去重与上限正确
- **PRD 承接** FR-20、FR-23（最近搜索词）、FR-19（扩展）、NFR-05 / 06 / 07

```mermaid
graph LR
    A["P0-01 持久化换血"] --> B["P0-02 位置树与路径"]
    B --> C["P0-03 状态与可信度"]
    C --> D["P0-04 拼音与搜索深化"]
```

## 5. 文件规模

**新建 25**（P0-01 十四 / P0-02 八 / P0-03 一 / P0-04 二）；**修改 21**（构建配置 2 + **F1 已交付源码 19**）；**删除 1**（`InMemoryStore.kt`）。

> 各任务「修改」按**首次计入**合计：P0-01 4 + P0-02 7 + P0-03 4 + P0-04 6 = 21（`ItemRepositoryImpl.kt` 被 P0-03 / P0-04 复用，仍只计一次；P0-03 / P0-04 行内标注的 5 / 7 是**该任务触及数**）。

> 注意工程量分布：本次**主要成本花在改 F1 已交付的代码**（19 个文件），新建的 25 个里有 14 个是 Entity / DAO 这类模板化文件。逐一清单见 §4 各任务，不另设重复的文件清单章。

## 6. 依赖版本（新增）

| 依赖 | 版本 | 结论 |
|---|---|---|
| `androidx.room:{room-runtime, room-ktx, room-compiler(ksp)}` | 2.8.4 | 旧分册已核验：`minSdk 23 ≤ 24` ✓；**无 `minCompileSdk=37` 约束** ✓；可与 compileSdk 36 / AGP 8.13.2 共存 ✓。异常时回退 2.8.3 |
| `androidx.room:room-testing` | 2.8.4 | `androidTestImplementation`（种子与 DB 验证） |
| `com.belerweb:pinyin4j` | 2.5.1 | **选型调整（2026-09-29，实现期）**：原拟 `com.github.houbb:pinyin:0.4.0`，但其 API（包路径与 `toPinyin` 的返回形态）**在本机无法离线核验**，为避免不可编译的猜测，改用签名确定者 —— `PinyinHelper.toHanyuPinyinStringArray(char, format): String[]?`（返回 null 即非汉字）。Maven Central 直取、**纯 Java（可 JVM 单测）**、Apache-2.0 |
| KSP 参数 | — | `room.schemaLocation = $projectDir/schemas`、`room.generateKotlin = true` |

**工具链基线不变**：AGP 8.13.2 / Kotlin 2.2.21 / compileSdk 与 targetSdk 36 / minSdk 24 / Compose BOM 2025.12.01 / `core-ktx` 钉 1.18.0（勿升 1.19.0）。`settings.gradle.kts` **不新增仓库**；**不新增任何 `uses-permission`**。

## 7. 与 F1 的接缝（本次触及的 F1 已交付文件 = 21：修改 19、删除 1、保留不改 1）

| F1 文件 | 本次改动 |
|---|---|
| `data/repository/ItemRepositoryImpl.kt` | 内存源 → DAO；`createItemQuick` 补 `locationId`；`observeSearchDocs` 拼入路径 / 拼音 / 时间 |
| `data/repository/CategoryRepositoryImpl.kt` | 常量 8 条 → `category` 表 |
| `data/memory/InMemoryStore.kt` | **删除** |
| `data/memory/BuiltInData.kt` | 保留为常量真源，改由 `SeedCallback` 引用（**不改内容**） |
| `data/repository/ItemRepository.kt` | 接口增 `setStatus` / `restore` |
| `di/RepositoryModule.kt` | 增 `LocationRepository` 绑定 |
| `ui/navigation/ShounaRoute.kt` | +1 个 `@Serializable` 路由键 |
| `ui/navigation/RouteHandler.kt` | +1 个 `composable` 注册 + `goLocationBrowse()` |
| `ui/screen/add/QuickAddScreen.kt` / `QuickAddViewModel.kt` | 位置条（**必填**、默认预选最近使用）、选择弹层 + **新建位置**、`locationId` 入参、`canSave` 加位置条件 |
| `ui/screen/itemdetail/ItemDetailScreen.kt` / `ItemDetailViewModel.kt` | 面包屑、两个动作、`gone` 恢复、「最后变动」、超期标记 |
| `ui/screen/home/HomeScreen.kt` | 增设「更多」折叠区 |
| `ui/screen/search/SearchScreen.kt` / `SearchViewModel.kt` | 结果行补路径 / 时间 / ⚠；空态最近搜索词 |
| `ui/component/ItemRow.kt` | 补路径、时间、⚠ |
| `util/TimeUtil.kt` | 增超期判定与「最后变动」文案（既有相对时间分档不动） |
| `domain/search/{SearchDoc, SearchIndex, SearchScorer, MatchType}.kt` | 拼音与路径字段、拼音权重档 |

> **反向不动**：`ui/theme/*`、`MainActivity.kt`、`ShounaApplication.kt`、`di/AppModule.kt`、F1 的 4 个单测文件。

## 8. 本次裁决与落差登记

### 8.1 本次裁决（本页定的口径）

| # | 事项 | 结论与理由 | 止损（要改回时动哪里） |
|---|---|---|---|
| 1 | 表数 | **5 张**。`placement` 随一物一处取消；`item_alias` / `tag` 非 P0；`recent_location` 用 `location.last_used_at` 可完全替代 | 需独立记录「位置访问频率」时再加表，不动 `item` |
| 2 | `location.path` | **不物化**。P0 的 FR-01 只要「直属物品 + 子位置」（索引可覆盖）；ID 序列路径服务的是「子孙范围查询」，属 P1 的 FR-21 / 28 / 35 | P1 需要时加列 + 一次性迁移到 `version = 2` |
| 3 | 最近位置 | 用 `location.last_used_at` | — |
| 4 | 删位置 | 以**子树**为单位；「一并删除」改为**标记 `gone` 保留**，以守 PRD 06 §5.2「非删除式失效」与可恢复性 | 改 `LocationRepository.delete` 内一处 |
| 5 | 内置位置 | **仅哨兵 `未指定位置` 1 条**（`待归位区` **不再种子化** —— 它属 P1 的 FR-06 / FR-38，无 UI 时预置只会造出一个「看得见却用不了」的位置）。哨兵不可删、不可选、不可移入自身子树 | 需要时补种子行 + `version = 2` 迁移 |
| 6 | 状态范围 | 只交 F1 档两动作 + `gone` 恢复；「待归位」与去向备注属 F2 形态，与 FR-06 / FR-38 强绑 → 留 P1 | `TO_BE_PUT_BACK` 与 `isActive` 已在 F1 就位，P1 只补 UI + 一次写 |
| 7 | 超期阈值 | 存 `app_config.threshold_months`（默认 6）——FR-27 是 P0，必须有真源；P1 的 FR-29 要的 3 / 6 / 12 复用同一行 | — |
| 8 | F2 入口门控 | **不做阈值门控**，位置浏览恒显。首启预置 6 个位置 → 「位置总数 ≥ 3」恒真，门控是永真死代码（F1 文档 D-1 已登记该冲突）；「显示完整功能」开关属 FR-47（P1） | P1 恢复门控 = 首页「更多」区加 1 个条件判断 |
| 9 | 位置选择器 | **底部弹层**内嵌同一棵树组件：少一条路由变体、少一个跨页结果回传通道；树可能很深，弹层高度够用。M7「录入过程零弹窗」约束的是**系统主动弹窗**，用户点位置条属主动触发 | 改为独立选择页 = +1 路由 + `savedStateHandle` 回传 |
| 10 | 拼音落点 | 物品名**持久化**拼音列；位置名 / 分类名**构建时实时拼音化 + 同名缓存**，不落库（守 PRD 08 §7.6 A-2：改名与移动的代价与物品数量脱钩） | 索引重建超预算时加内存缓存层，仍不违反 A-2 |
| 11 | 多音字 | 取默认读音；非汉字原样保留 | — |
| 12 | 种子时机 | `RoomDatabase.Callback.onCreate()`：语义天然匹配「仅全新安装首次」，不引入初始化迁移 | — |
| 13 | Room 的验证 | 走 **androidTest**（种子 / DB / schema），JVM 单测只覆盖纯逻辑——避免引入 Robolectric（新增依赖 + AGP 版本兼容风险），`room-testing` 已在选型内。F1 文档写「无仪器测试目录」是 **F1 范围**的陈述，本页起启用 androidTest，**不改 F1 文档** | 若坚持纯 JVM：换 Robolectric |
| 14 | 详情页布局 | **不预建**「⋯更多」折叠区：本次新增字段只有 1 个时间，包一层折叠多一次点击 | P1 补分类 / 别名 / 数量 / 待归位 / 移动时再引入折叠 |
| 15 | **位置必填**（本次新增） | 用户 2026-09-29 明确要求「所有物品都必须手动选择一个位置，或者输入位置」。落地：`canSave` = 「名称非空 ∧ 位置已选」；位置条默认预选 `last_used_at` 最近者、**首次为空 → 保存被拦**；弹层内提供「＋ 新建位置」兑现「或输入位置」。**「保存并继续」保留上次位置**，使「每条都有真实位置」与「连续录入不打断」（PRD US-02）并存 | 改回「可留空」= 放开 `canSave` 的位置条件 + 让哨兵重新进入选择器（1 处 UI 过滤 + 1 处校验） |
| 16 | 拼音库选型（本次新增） | 见 §6：`com.github.houbb:pinyin` → `com.belerweb:pinyin4j`，理由是**实现期需要可离线确定的 API 签名** | 换回 houbb 前须先核验其 API |

### 8.2 落差（本页不做，P1 起）

- **PRD 侧 FR 行已同步（2026-09-29 用户授权解除冻结）**：FR-01 / 05 / 09 / 10 / 11（位置必填）、FR-20（`dsf` → `dfs`）、FR-22（筛选去掉「是否有待补详情」）、FR-23（最近搜索词 = 搜索页空态，暴露层改 F1）、FR-25（枚举名已定）、FR-27（详情页并列展示双时间）、FR-33（去掉「待补详情数」）、FR-37（按 3 态改写）均已在 `prd/05` §4.1～§4.6 就地改写，登记见 `prd/05` §4.11。
- **「待补详情」概念已废弃（2026-09-29 用户裁示）**：FR-13 → 删除（P1 **14 → 13**、本期交付 **29 → 28**）；`prd/08` §7.1.1 判定节与「信息完整度 / 待补详情」字段行、`prd/09` C-7、`prd/10` 约束 A-4 一并撤除。**本页不实现该项目**。
- **⚠️ 恢复入口的可达性未结**：`restore` 只挂在物品详情页，而搜索结果与位置浏览列表均过滤 `gone`。因此详情页内点「✕ 不在了」后**可就地撤回**（按钮即刻转为「恢复」），但**跨会话找回已 `gone` 的物品无 UI 入口**（登记见 `prd/06` §5.2 与 `prd/12` U-6）。

- **递归计数**：P0 只出**直属**物品；FR-21 的「本层 N 件 / 含子层共 M 件」随 §8.1-2 的 `path` 物化一起做。
- **FR-28 按位置批量确认**：P-BROWSE 本次**不放该入口**。
- **FR-22 搜索筛选**：本次搜索页无筛选 chips。
- **FR-29 超期清单页**：本次超期标记只在详情页与列表行，**无集中清单**。
- **FR-42 / 43 导出导入：已永久废弃（用户 2026-09-29 裁示「直接删除该功能」）**。本页原登记「P1 里优先级最高的一项」**作废**；FR-40（数据导出报告）一并删除。数据可靠性**只由 FR-41 本地持久化（Room 本地存储）承担**，本机之外不存在任何备份或迁移通道（登记见 `prd/05` §4.11）。
- **依赖体积**：Room + pinyin4j（**最终选型**，见 §6 与 §8.1 第 16 条）对 APK 的实际增量 —— **已实测**：APK 14,068,892 B，较 F1 基线 13,385,039 B 增 **+683,853 B**。

## 9. 整体验收

| 项 | 口径 |
|---|---|
| 构建 | `:app:assembleDebug` + `:app:testDebugUnitTest` 成功；androidTest 通过 |
| FR-41 | 强杀进程后重开：位置树、物品、状态、最近搜索全部完整 |
| FR-01 / 02 / 03 / 05 | 建 3 层位置 → 录入 → 详情页显示完整路径 → 改名后路径即时跟随 → 删除位置后无孤儿 |
| FR-11 / 23 | 录入页位置条预选最近位置；搜索页空态出现最近搜索词 |
| **位置必填** | 全新安装后未选位置时「保存并继续」**不可用**；选中或新建位置后可保存；连续录入 5 件位置一致；选择器与浏览页**均不出现**「未指定位置」 |
| FR-20 | `dfs` / `dianfengshan` / `风扇` 均命中「电风扇」 |
| FR-25 / 27 | 「不在了」后从搜索结果与位置浏览列表消失；超期标记在 6 个月边界两侧正确。⚠️ **恢复入口的可达性未结**：`restore` 只挂在物品详情页，各列表均过滤 `gone` —— 详情页内标记后可就地撤回，**跨会话找回无 UI 入口**（登记见 `prd/06` §5.2 与 `prd/12` U-6） |
| 不回归 | 连续录入 5 件 ≤ 40 秒；1000 件搜索 ≤ 300ms（JVM 微基准）；永久浅色、字号锁定、系统栏避让三项不变 |

---

*本文档为 P0 剩余 10 条需求的架构规划与任务分解，不含代码文件。P1 / P2 / V2 不在本页范围，不构成对后续版本的承诺。*
