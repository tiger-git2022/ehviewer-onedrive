# EhViewer OneDrive 同步：设计与实现要求

> 本文档交给负责实现的云端编码会话，开工前请完整读完。
> - 代码基于 Codeberg 上游 `EhViewer/EhViewer` 的 main 分支（提交 `6a0a127`），本仓库是用户的 fork：`tiger-git2022/ehviewer-onedrive`。
> - 用户之前的 Mihon fork（`tiger-git2022/mihon`，`custom` 分支，提交 `0be8c6884`）已经实现了可用的「OneDrive 书架同步」，方案是 MSAL + Graph approot + ETag + 三方合并。
> - 本设计沿用这套方案，只是同步范围从「只同步书架」扩大到：全部设置、本地收藏、历史和阅读进度、快速搜索和过滤器、搜索建议。
> - 下载记录例外：每台设备各自独立，只上传供其他设备查看，用户手动选择后才导入，见第 5 节。
> - 支持按用户设定的间隔自动同步，见第 6 节。

## 0. 硬性约束

- **语言和代码风格**：用 Kotlin 实现，风格和现有代码保持一致：
  - 用手写的 `object` 单例，不引入 DI 框架；
  - Compose 页面用 `@Destination<RootGraph>` 注册；
  - 界面文案放在 moko-resources。
- **不改 Room 数据库结构**（保持 version 23）。同步状态另存到独立的文件或 DataStore，这样以后还能和官方版互相覆盖安装。
- **保持 `applicationId = moe.tarsin.ehviewer` 不变**，release 签名继续用仓库里的 `app/keystore/androidkey.jks`：
  - 这个 keystore 和官方发布版用的是同一个，所以用户可以**直接覆盖安装到官方 EhViewer 上，数据不丢**；
  - versionCode 不要调低（当前是 180063）。
- **CI 必须保持通过**：
  - `.github/workflows/build.yml` 在每次 push 到 `main` 时运行 `./gradlew assembleDefaultRelease`，并发布 `nightly` 预发布版；
  - 每次改动后都要检查 Actions 运行结果。
- **新增文案**至少加到这两个文件：
  - `core/i18n/src/commonMain/moko-resources/base/strings.xml`（英文）
  - `zh-rCN/strings.xml`（简体中文）

## 1. 认证（MSAL，和 Mihon 一致）

**依赖**
- 引入 `com.microsoft.identity.client:msal:8.x`（Mihon 用的是 8.4.1），加到 `gradle/libs.versions.toml`。
- 排除 `com.microsoft.device.display:display-mask`，仓库里解析不到这个包。
- 检查 `settings.gradle.kts` 的仓库列表缺什么。MSAL 的传递依赖可能不在 google 和 mavenCentral 里，Mihon 当时额外加了 jitpack。

**登录与 token**
- 使用单账户模式 `createSingleAccountPublicClientApplication`。
- 交互登录用 `signIn`，之后每次请求前用 `acquireTokenSilent` 获取 token。
- token 交给 MSAL 缓存，应用自己只保存账户 id 和显示名。

**Azure 应用注册**
- **复用 Mihon 的 client id**：`77f7046c-0a22-44b8-aa16-8b8416628c6c`。
- 用户需要在 Azure 里给它加一个 Android 平台，见第 10 节。

**配置文件** `app/src/main/res/raw/auth_config_single_account.json`
- `client_id`：同上
- `redirect_uri`：`msauth://moe.tarsin.ehviewer/oCaXb5ekeLseg%2B5DxDHor8aINqE%3D`
- `account_mode: SINGLE`、`broker_redirect_uri_registered: false`、`authorization_user_agent: DEFAULT`
- authority：`AAD` / `AzureADandPersonalMicrosoftAccount` / `common`

**Manifest**
- 注册 `com.microsoft.identity.client.BrowserTabActivity`。
- intent-filter 写 `scheme="msauth" host="moe.tarsin.ehviewer" path="/oCaXb5ekeLseg+5DxDHor8aINqE="`。注意 path 用的是未编码的哈希。

**签名信息**（已用 keytool 实际算过）
- SHA1：`A0:26:97:6F:97:A4:78:BB:1E:83:EE:43:C4:31:E8:AF:C6:88:36:A1`
- Base64：`oCaXb5ekeLseg+5DxDHor8aINqE=`

**哪些构建能登录**
- 只有 `defaultRelease` 变体需要能登录。
- debug 包（`.debug` 后缀，debug 签名）和 marshmallow 变体（`.m` 后缀）都登录不了。在这些包上，界面要显示「此构建不支持 OneDrive 同步」，不能崩溃。
- 不要为它们登记 redirect。

**Scope**
- 使用 `Files.ReadWrite`（和 Mihon 一致，个人账户可以访问 approot）。

**混淆规则**
- release 开启了 minify，要补上 MSAL 需要的 ProGuard/R8 规则，否则 release 包运行时可能崩溃。

## 2. 云端存储位置

**路径**
- Graph 接口：`https://graph.microsoft.com/v1.0/me/drive/special/approot:/ehviewer/<文件>:/content`
- **必须放在 `ehviewer/` 子目录里。** Mihon 和这里用的是同一个 client id，所以 approot 是同一个文件夹，而 Mihon 已经在它的根目录放了 `mihon-library-sync.json`。

**文件格式**
- 文件名 `ehviewer/sync-v1.json.gz`：用 kotlinx.serialization 生成 JSON，再用 gzip 压缩。项目里已经有 `kotlinx-serialization-json` 依赖。

**HTTP 客户端**
- 可以用现有的 Ktor（`EhApplication.ktorClient`，底层是 OkHttp 或 Cronet），也可以单独建一个 OkHttp。
- **不要**通过带 E-Hentai cookie 和自定义 UA 的那个客户端发请求。建议单独用一个干净的客户端。

**下载**
- 用 GET。
- 404 视为云端为空。
- 记下返回的 `ETag`。

**上传**
- 用 PUT，带 `If-Match: <etag>`；第一次上传改带 `If-None-Match: *`。
- 文件超过 4 MB 时，改用 `createUploadSession` 分片上传。
- 遇到 412：自动重新下载、重新合并、再上传，最多重试 3 次。
- 解析 Graph 返回的 `error.code` 和 `message`，放进错误提示里。

## 3. 同步数据模型（`SyncFile`，version = 1）

```
SyncFile {
  version: Int = 1            // 云端版本比本地新时拒绝同步，提示用户升级应用
  updatedAt: Long             // 毫秒
  deviceId: String, deviceName: String
  settings: Map<String, SyncSetting>          // key -> { value: JsonElement, type, modifiedAt }
  galleries: List<GalleryEntity 等价字段>      // 只包含下面各项引用到的画廊（外键依赖）
  localFavorites: List<{gid, time}>
  history: List<{gid, time}>
  progress: List<{gid, page, modifiedAt}>
  // 注意：下载记录和下载标签不在这个共享文件里，见第 5 节（每台设备单独一个文件）
  quickSearches: List<QuickSearch 字段（不含 _id）>
  filters: List<{mode, text, enable}>
  searchSuggestions: List<{query, date}>     // search_database.db 中的 suggestions 表
}
```

### 现有代码的相关事实（来自调查，行号请自行核对）

**设置**
- 设置对象：`app/src/main/kotlin/com/hippo/ehviewer/Settings.kt` 中的 `object Settings : DataStorePreferences`。
- 基类：`core/data/src/commonMain/kotlin/com/ehviewer/core/preferences/DataStorePreferences.kt`，提供 `data: Flow<Snapshot>`、`snapshot()` 和 `withMutableSnapshot{}`。
- DataStore 文件：`moe.tarsin.ehviewer_preferences.preferences_pb`。
- 设置变化引发的副作用在 `SettingsCollector.kt`。

**数据库**
- 定义在 `core/data/src/commonMain/kotlin/com/ehviewer/core/database/Database.kt`：
  - `EhDatabase` v23，文件名 `eh.db`；
  - `SearchDatabase`，文件名 `search_database.db`。
- 实体在 `.../database/model/`，DAO 在 `.../database/dao/`。
- 统一入口是 `app/src/main/kotlin/com/hippo/ehviewer/EhDB.kt`。

**可参考的现有导入逻辑**
- `EhDB.importDB` 是一个合并式导入，全部用 insertOrIgnore。
- 可以照它的顺序写入各表，但这里必须实现真正的「更新」和「删除」语义。

**下载管理器**
- `DownloadManager`（`download/DownloadManager.kt`）**启动时会把下载列表全量载入内存**。
- 因此下载记录和标签必须通过它的 API 修改，例如 `addDownload`、`addDownloadLabel`，以及删除、改标签等方法。
- 直接改数据库会导致内存里的数据和数据库不一致。

## 4. 合并策略（三方合并：本地 / 云端 / 基线）

**基线**就是上一次同步成功的结果，存在本地：
- 内容文件：`files/onedrive_sync/baseline.json.gz`；
- 另有一个同步状态 DataStore。

基线的使用规则：
- **切换账户时清空基线**；
- 没有基线时（首次同步），只做并集，不删除任何东西。

| 数据 | 主键 | 规则 |
|---|---|---|
| 设置 | 偏好键 | 每个键按 `modifiedAt` 取较新的一方（来源见表下说明）。 |
| 本地收藏 | gid | 用集合加基线判断增删：<br>· 基线里有、某一方没有：这一方删除了它；<br>· 基线里没有、某一方有：这一方新增了它；<br>· 两边同时改动：新增优先于删除；<br>· `time` 取较早的值。 |
| 历史 | gid | `time` 取较大值。删除靠基线判断，单条删除和「清空历史」都会同步。 |
| 阅读进度 | gid | 每条按 `modifiedAt` 取较新的一方。时间戳可以用同一 gid 的历史时间代替，也可以在进度变化时自己记录。 |
| 快速搜索 | name | 集合加基线。字段按时间取较新的一方。顺序以云端为准，本地独有的追加到末尾。 |
| 过滤器 | (mode, text) | 集合加基线。`enable` 按时间取较新的一方。 |
| 搜索建议 | query | 取并集，date 取较大值，只保留最新的 N 条（沿用应用现有上限）。 |
| 画廊 | gid | 本地没有就插入；有冲突时保留字段更完整的一条。 |

**设置的时间戳从哪来**
- 应用启动时，`OneDriveSync` 订阅 `Settings.data`。
- 每次数据变化，对比前后两个快照，把发生变化的同步键记为 `key -> now`，写入同步状态 DataStore。
- **应用云端设置时要暂停记录**，可以用一个标志位实现，避免两台设备来回覆盖。

**不同步的设置键**（设备专属或敏感信息，做成显式黑名单）
- 下载目录：`image_scheme`、`image_authority`、`image_path`、`image_query`、`image_fragment`
- 账户显示信息：`has_signed_in`、`display_name`、`avatar`
- `archive_passwds`（压缩包密码）、`clipboard_text_hash_code`、`last_update_time`
- 其他明显只属于本机的键：请逐个检查 `Settings.kt` 第 64–202 行，拿不准的一律不同步。
- UI 状态 DataStore `"Remembered"`（`ui/tools/DateStoreState.kt`）不同步。
- **任何情况下都不同步 E-Hentai 登录 cookie**（`ipb_member_id`、`ipb_pass_hash`、`igneous`，存在 WebView 的 CookieManager 里）。

## 5. 下载记录：每台设备各自独立，按需导入

**原则：下载记录不做合并。** 每台设备的下载列表只属于它自己，其他设备的记录**永远不会自动写进本机**。只有用户主动选择后才导入。

**上传（每台设备只写自己的文件）**
- 每次同步时，把本机的下载列表上传到 `ehviewer/devices/<deviceId>.json.gz`：
  ```
  DeviceDownloads {
    version: Int = 1
    deviceId, deviceName      // deviceName 默认取设备型号，可在同步设置页改名
    updatedAt: Long
    labels: List<{label, position}>
    downloads: List<{gid, label?, time, finished: Boolean, artists[]}>
    galleries: List<GalleryEntity 等价字段>   // 这些下载引用的画廊元数据
  }
  ```
- 每台设备只写自己的那个文件，所以不会和其他设备冲突。上传时仍然带 ETag，防止同一台设备的多个同步任务互相覆盖。
- `deviceId` 是首次使用时生成的 UUID，存在同步状态 DataStore 里。**切换账户时不要重新生成。**
- 只上传数据库里的元数据，**不上传漫画文件**。`DOWNLOAD_DIRNAME` 和下载目录是本机专属的，也不上传。

**查看和导入其他设备的下载记录**
- 列出设备：用 `GET /me/drive/special/approot:/ehviewer/devices:/children`，排除本机自己的文件。
- 同步设置页里加一个入口「其他设备的下载记录」，进入后：
  1. 显示设备列表：设备名、最后更新时间、下载条数。
  2. 点进某台设备：按标签分组显示它的下载列表（标题、缩略图、是否已完成），本机已有的条目标注「已存在」。
  3. 支持多选、按标签全选、全选，然后点「导入到本机」。
- 导入规则：
  - 通过 `DownloadManager` 的 API 加入本机下载列表，状态设为**暂停/未开始**，不自动下载。用户在本机点「开始」才会重新下载。
  - 保留原标签；本机没有这个标签时，自动用 `addDownloadLabel` 创建。
  - 本机已经有的 gid 一律跳过，不改本机记录的任何状态。
  - 导入完成后提示导入了多少条、跳过多少条。

**可选的「订阅」某台设备**（默认关闭，在设备详情页里逐台设置）
- 开启后，本机每次同步时，自动把这台设备**新增的**下载记录导入本机（规则同上：暂停状态，已有的跳过）。
- 「新增」是指相对上次导入时这台设备的记录集合多出来的那些 gid。每台被订阅设备的已导入 gid 集合存在本机同步状态里。
- **订阅只导入新增，不同步删除。** 对方删除记录，不会影响本机。
- 用户在本机删掉一条导入过的记录后，下次同步不会再把它导回来。

**清理**
- 设备详情页提供「从云端移除此设备的记录」，带二次确认，用于清掉已经不用的旧手机。
- 本机退出登录时，不自动删除本机在云端的设备文件。

## 6. 同步流程和触发方式

**流程**（用一个 `Mutex` 串行执行，在 IO 线程运行）
1. 用 `acquireTokenSilent` 获取 token。
2. GET 云端文件，记下 ETag。
3. 读取基线。
4. 收集本地状态。
5. 三方合并。
6. PUT 上传合并结果，带 `If-Match`；遇到 412 回到第 2 步。
7. **上传成功之后**再把结果应用到本地，顺序为：设置 → 画廊 → 收藏/历史/进度 → 快速搜索/过滤器 → 搜索建议。
8. 保存新的基线、最后同步时间和结果。
9. 上传本机的下载记录文件 `devices/<deviceId>.json.gz`（第 5 节）。如果订阅了其他设备，读取它们的文件并导入新增记录。这一步失败不影响前面共享数据的同步结果，单独记录错误即可。

**手动触发**
- 设置页的「立即同步」按钮。
- 本地收藏页顶栏的同步图标。

**自动触发**（「自动同步」总开关，默认开启；关闭后只能手动同步）

1. **按间隔定时同步（核心功能）**
   - 用 WorkManager 的 `PeriodicWorkRequest` 加 `CoroutineWorker` 实现。可以参照 `dailycheck/DailyCheckWork.kt`。
   - 用户可以在设置里选择间隔：15 分钟、30 分钟、1 小时、3 小时、6 小时（默认）、12 小时、24 小时。
     - WorkManager 周期任务最短 15 分钟，系统可能因省电略有延后，这一点要在选项说明里写明。
   - 用 `enqueueUniquePeriodicWork`，改间隔时用 `UPDATE` 或 `CANCEL_AND_REENQUEUE` 重新排队。
   - 约束：要求联网；开启「仅 Wi-Fi」时要求不计流量网络（`NetworkType.UNMETERED`）。
   - 关闭自动同步、退出登录时取消这个任务；登录成功、开启自动同步时重新排队。
   - 应用每次启动时都确认一下任务已排队（参照 `SettingsCollector.kt` 里每日签到任务的写法）。
2. **应用启动时同步一次**（已登录且联网时）。
3. **数据变化后同步**：收藏、历史、设置等发生变化后，防抖 60 秒再同步一次。
   - 这项单独做一个开关，默认开启。
   - 下载列表变化只需要触发上传本机的设备文件。

- 提供「仅 Wi-Fi 同步」选项，对以上所有自动同步都生效，手动同步不受限制。

**结果提示**
- 手动同步用 Toast 或 Snackbar 提示结果，按类别显示新增、更新、删除的数量。
- 后台同步不弹提示，只记录结果。

## 7. 界面

**新页面**
- 新建 `app/src/main/kotlin/com/hippo/ehviewer/ui/settings/OneDriveSyncScreen.kt`，加上 `@Destination<RootGraph>`。
- 在 `SettingsScreen.kt` 的「高级」前面加一个 `PreferenceHeader` 作为入口。

**页面内容**
- 账户：登录、退出，显示当前账户名。
- 立即同步，以及上次同步的时间和结果。
- 自动同步：
  - 总开关；
  - 同步间隔（下拉选择，见第 6 节）；
  - 数据变化后自动同步；
  - 仅 Wi-Fi。
- 分类开关，默认全部开启：
  - 设置
  - 本地收藏
  - 历史和阅读进度
  - 快速搜索和过滤器
  - 搜索建议
  - 上传本机的下载记录（只控制是否把本机列表传到云端，供其他设备查看）
- 下载记录：
  - 本机设备名（可修改）；
  - 「其他设备的下载记录」入口（第 5 节）。
- 「清除云端数据」，需要二次确认。
- 一段简短说明：数据存放在 OneDrive 应用文件夹 `应用/<应用名>/ehviewer/` 中，漫画文件不会同步。

**控件**
- 复用 `ui/settings/PreferenceWidgets.kt` 里的 `Preference`、`SwitchPreference`、`WorkPreference` 等控件。

## 8. fork 相关的配套修改（一并完成）

- `app/build.gradle.kts` 第 50 行左右的 `repoName`：现在只去掉 `https://codeberg.org/` 前缀，需要同时去掉 `https://github.com/`。
- `updater/AppUpdater.kt` 现在用的是 Codeberg API（`https://codeberg.org/api/v1/repos/...`）。二选一：
  - 改成用 GitHub Releases API 检查 `tiger-git2022/ehviewer-onedrive` 的更新，包括 `nightly` 预发布版和 `v*` 标签版，APK 文件名形如 `EhViewer-<版本>-arm64-v8a.apk`；
  - 或者默认**关闭**从官方上游检查更新，避免被官方版覆盖。
- `ui/settings/AboutScreen.kt` 的 `REPO_URL` 现在指向 Codeberg，改成 GitHub。

## 9. 测试

- 为纯合并逻辑 `OneDriveSyncMerger` 写 JVM 单元测试，至少覆盖以下场景：
  - 首次同步时取并集；
  - 只有一方新增或删除；
  - 两方同时改动时新增优先；
  - 历史时间取最大值；
  - 设置按时间取较新的一方；
  - 黑名单中的键不会被上传；
  - 云端版本过新时拒绝同步；
  - 导入其他设备下载记录时，本机已有的 gid 被跳过、缺失的标签被创建；
  - 订阅模式只导入新增；本机删除导入过的记录后不会被再次导入。
- CI 构建必须通过。

## 10. 需要用户手动完成的事（代码做不到）

1. 在 Azure 门户中给 Mihon 用的那个应用（client id `77f7046c-...`）添加 Android 平台：
   - 打开路径：应用注册 → 选中该应用 → 身份验证 → 添加平台 → Android；
   - 包名填 `moe.tarsin.ehviewer`；
   - 签名哈希填 `oCaXb5ekeLseg+5DxDHor8aINqE=`。
2. 在手机上安装 APK。可以直接覆盖安装到官方 EhViewer 上，数据保留。
3. 每台设备登录同一个微软账户，然后逐台各同步一次。
