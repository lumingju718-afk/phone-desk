# Changelog

## 0.6.0 — 2026-11-01

- 新增无障碍服务 `DeskAccessibilityService`：远程 UI 控制
  - 手势：点击、长按、滑动
  - 全局操作：返回、主页、多任务、通知栏、快捷设置
  - 节点树读取、按文字点击、向输入框填文本
  - 截图（API 30+，JPEG base64）
- 新增 HTTP 接口：`/ui/on`、`/ui/shot`、`/ui/dump`、`/ui/tap`、`/ui/long`、
  `/ui/swipe`、`/ui/global`、`/ui/text`、`/ui/click`
- 主屏与终端页显示无障碍开关状态，可一键跳转系统设置
- 版本号 0.6.0（versionCode 7）

## 0.5.1 — 2026-11-01

- 新增 GitHub Actions 自动构建（`build.sh` + `.github/workflows/build.yml`）
- push / PR / 打 `v*` 标签自动产出 APK；打标签时自动挂到 Release
- CI 打包压缩 `classes.dex`，产物体积与本地构建一致

## 0.5.0 — 2026-11-01

首个归档版本（仓库初始化）：

- 主屏服务器状态面板：时间、电量/温度、内存、存储、运行时长、IP
- AstrBot / NapCat / SSH 端口探活，ESP32 充电状态联动
- 应用内终端（应用 UID 执行 shell）
- 前台服务 + HTTP 控制端（`/ping`、`/exec`）
- Termux 桥接（`RUN_COMMAND` + `/callback` 回传），需 `RUN_COMMAND` 运行时权限
- 开机自启、默认桌面引导、应用抽屉
- 签名配置外置到 `signing.properties`，构建脚本支持全新克隆自举
