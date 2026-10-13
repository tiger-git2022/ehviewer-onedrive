# EhViewer OneDrive（tiger-git2022/ehviewer-onedrive）

<p align="center">
  <a href="https://github.com/tiger-git2022/ehviewer-onedrive/releases/tag/nightly"><img src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD%E6%9C%80%E6%96%B0%E7%89%88%20%2F%20Download-nightly-2ea44f?style=for-the-badge" alt="下载最新版 / Download latest"></a>
</p>

**[⬇ 下载最新 APK / Download the latest APK](https://github.com/tiger-git2022/ehviewer-onedrive/releases/tag/nightly)**：大多数手机选 `EhViewer-nightly-…-arm64-v8a.apk`，不确定就选 `…-universal.apk`。/ Most phones: `EhViewer-nightly-…-arm64-v8a.apk`; if unsure, `…-universal.apk`.

[中文](#中文) | [English](#english) | [上游 EhViewer 说明 / Upstream README](../README.md)

## 中文

基于 [EhViewer](https://codeberg.org/EhViewer/EhViewer)（[FooIbar/EhViewer](https://github.com/FooIbar/EhViewer)）的个人分支，正在加入 **OneDrive 同步**：设置、本地收藏、历史和阅读进度、快速搜索和过滤器、搜索建议在多台设备之间同步；下载记录每台设备各自保存，手动选择后才导入。设计见 [docs/onedrive-sync-design.md](../docs/onedrive-sync-design.md)。

### 下载与更新

- 每次推送 `main` 都会由 GitHub Actions 编译，并重新发布 [`nightly`](https://github.com/tiger-git2022/ehviewer-onedrive/releases/tag/nightly) 预发布版。
- 包名仍是 `moe.tarsin.ehviewer`，签名和官方发布版相同，可以直接覆盖安装到官方 EhViewer 上，数据不丢。
- 应用内的检查更新指向本仓库：选择 CI 更新渠道时跟随 `nightly`。

## English

Personal fork of [EhViewer](https://codeberg.org/EhViewer/EhViewer) ([FooIbar/EhViewer](https://github.com/FooIbar/EhViewer)) that is adding **OneDrive sync**: settings, local favorites, history and reading progress, quick searches and filters, and search suggestions sync across devices; download records stay per device and are imported only when you pick them. See [docs/onedrive-sync-design.md](../docs/onedrive-sync-design.md) (Chinese) for the design.

### Download and updates

- Every push to `main` is built by GitHub Actions and republished as the [`nightly`](https://github.com/tiger-git2022/ehviewer-onedrive/releases/tag/nightly) pre-release.
- The package is still `moe.tarsin.ehviewer` with the same signing key as the official release, so it installs over the official EhViewer and keeps your data.
- The in-app update check points at this repository; the CI channel follows `nightly`.
