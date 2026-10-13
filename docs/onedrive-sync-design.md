# EhViewer OneDrive 同步：设计与实现要求

> 本文档交给负责实现的云端编码会话，开工前请完整读完。
> - 代码基于 Codeberg 上游 `EhViewer/EhViewer` 的 main 分支（提交 `6a0a127`），本仓库是用户的 fork：`tiger-git2022/ehviewer-onedrive`。
> - 用户之前的 Mihon fork（`tiger-git2022/mihon`，`custom` 分支，提交 `0be8c6884`）已经实现了可用的「OneDrive 书架同步」，方案是 MSAL + Graph approot + ETag + 三方合并。
> - 本设计沿用这套方案，只是同步范围从「只同步书架」扩大到：全部设置、本地收藏、历史和阅读进度、下载记录、快速搜索和过滤器。

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
  downloadLabels: List<{label, position}>
  downloads: List<{gid, label?, time, state(是否已完成), artists[]}>
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
| 下载标签 | 标签名 | 集合加基线。顺序以云端为准，只在本地存在的追加到末尾。 |
| 下载记录 | gid | 集合加基线。标签变更按时间取较新的一方。具体语义见第 5 节。 |
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

## 5. 「下载记录同步」的语义

- 只同步数据库里的元数据，**不同步漫画文件本身**。
- `DOWNLOAD_DIRNAME` 和下载目录在每台设备上都不一样，**不同步**。

**从云端来的下载记录**
- 加入本地下载列表，状态设为**暂停/未开始**，不会自动开始下载。
- 保留它的标签。用户可以在新设备上点「开始」重新下载。

**本地已完成的记录**
- 只要本地记录是「已完成」且文件存在，就不要用云端数据覆盖它的状态。

**删除的同步**
- 其他设备删除下载记录时，本机默认**只删除记录，不删除本地文件**。
- 提供一个选项「其他设备删除下载记录时，同时删除本地文件」，默认关闭。

## 6. 同步流程和触发方式

**流程**（用一个 `Mutex` 串行执行，在 IO 线程运行）
1. 用 `acquireTokenSilent` 获取 token。
2. GET 云端文件，记下 ETag。
3. 读取基线。
4. 收集本地状态。
5. 三方合并。
6. PUT 上传合并结果，带 `If-Match`；遇到 412 回到第 2 步。
7. **上传成功之后**再把结果应用到本地，顺序为：设置 → 画廊 → 收藏/历史/进度 → 标签 → 下载 → 快速搜索/过滤器 → 搜索建议。
8. 保存新的基线、最后同步时间和结果。

**手动触发**
- 设置页的「立即同步」按钮。
- 本地收藏页顶栏的同步图标。

**自动触发**（有总开关，默认开启）
- 应用启动时同步一次（需要已登录且联网）。
- 收藏、历史、下载或设置发生变化后，防抖 60 秒再同步。
- 用 WorkManager 的 `CoroutineWorker` 定时同步，默认每 6 小时一次，要求联网。可以参照 `dailycheck/DailyCheckWork.kt` 来写。
- 提供「仅 Wi-Fi 同步」选项。

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
- 自动同步开关、同步间隔、仅 Wi-Fi。
- 分类开关，默认全部开启：
  - 设置
  - 本地收藏
  - 历史和阅读进度
  - 下载记录
  - 快速搜索和过滤器
  - 搜索建议
- 「删除下载记录时同时删除文件」，默认关闭。
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
  - 云端版本过新时拒绝同步。
- CI 构建必须通过。

## 10. 需要用户手动完成的事（代码做不到）

1. 在 Azure 门户中给 Mihon 用的那个应用（client id `77f7046c-...`）添加 Android 平台：
   - 打开路径：应用注册 → 选中该应用 → 身份验证 → 添加平台 → Android；
   - 包名填 `moe.tarsin.ehviewer`；
   - 签名哈希填 `oCaXb5ekeLseg+5DxDHor8aINqE=`。
2. 在手机上安装 APK。可以直接覆盖安装到官方 EhViewer 上，数据保留。
3. 每台设备登录同一个微软账户，然后逐台各同步一次。
