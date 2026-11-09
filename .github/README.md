# EhViewer OneDrive（tiger-git2022/ehviewer-onedrive）

<p align="center">
  <a href="https://github.com/tiger-git2022/ehviewer-onedrive/releases/latest"><img src="https://img.shields.io/github/v/release/tiger-git2022/ehviewer-onedrive?style=for-the-badge&label=%E4%B8%8B%E8%BD%BD%E6%9C%80%E6%96%B0%E7%89%88%20%2F%20Download&color=2ea44f" alt="下载最新版 / Download latest"></a>
</p>

**[⬇ 下载最新 APK / Download the latest APK](https://github.com/tiger-git2022/ehviewer-onedrive/releases/latest)**：大多数手机选 `EhViewer-v…-arm64-v8a.apk`，不确定就选 `…-universal.apk`。/ Most phones: `EhViewer-v…-arm64-v8a.apk`; if unsure, `…-universal.apk`.

[中文](#中文) | [English](#english) | [上游 EhViewer 说明 / Upstream README](../README.md)

## 中文

基于 [EhViewer](https://codeberg.org/EhViewer/EhViewer)（[FooIbar/EhViewer](https://github.com/FooIbar/EhViewer)）的个人分支，正在加入 **OneDrive 同步**：设置、本地收藏、历史和阅读进度、快速搜索和过滤器、搜索建议在多台设备之间同步；下载记录每台设备各自保存，手动选择后才导入。设计见 [docs/onedrive-sync-design.md](../docs/onedrive-sync-design.md)。

### 下载与更新

- 每次推送 `main` 都会由 GitHub Actions 编译，并发布一个新版本（[发布页](https://github.com/tiger-git2022/ehviewer-onedrive/releases)）。版本号是这个改版自己的 `vX.Y.Z`：修 bug 和小调整涨 Z，加新功能或合入上游新版本涨 Y，大改涨 X。基于的上游 EhViewer 版本写在每个版本的说明和「关于」页里。旧版本都保留（2026-11 以前的是 `v1.15.0-t003` 这种写法），每个版本附上距上一版的更新内容。
- 包名仍是 `moe.tarsin.ehviewer`，签名和官方发布版相同，可以直接覆盖安装到官方 EhViewer 上，数据不丢。
- 应用内的检查更新指向本仓库的最新版本，按上面的版本号比较新旧。

## English

Personal fork of [EhViewer](https://codeberg.org/EhViewer/EhViewer) ([FooIbar/EhViewer](https://github.com/FooIbar/EhViewer)) that is adding **OneDrive sync**: settings, local favorites, history and reading progress, quick searches and filters, and search suggestions sync across devices; download records stay per device and are imported only when you pick them. See [docs/onedrive-sync-design.md](../docs/onedrive-sync-design.md) (Chinese) for the design.

### Download and updates

- Every push to `main` is built by GitHub Actions and published as a new [release](https://github.com/tiger-git2022/ehviewer-onedrive/releases). Versions are the fork's own `vX.Y.Z`: fixes and small changes raise Z, new features or an upstream update raise Y, big changes raise X. The upstream EhViewer version each build is based on is in its release notes and on the About screen. Earlier releases stay (before November 2026 they were named like `v1.15.0-t003`), and each one lists the changes since the one before.
- The package is still `moe.tarsin.ehviewer` with the same signing key as the official release, so it installs over the official EhViewer and keeps your data.
- The in-app update check follows the latest release of this repository and compares these version numbers.
