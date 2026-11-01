# Changelog

## 0.11.0 — 2026-11-01

- 消息页体验完善（承接 0.10.0 的对话框架）：
  - **消息分组**：同一方连续发言只在组首显示「助手 / 我」，气泡间距收紧
  - **日期分隔**：跨天自动插入「今天 / 昨天 / M月D日」
  - **Markdown 渲染**：`**粗体**`、`` `行内代码` ``、```` ``` ```` 围栏代码块（等宽字体 + 深色底，独立段落）
  - **通知栏直接回复**：新消息通知带输入框，回完直接入队并触发应答引擎
  - 通知通道升级为 HIGH，正文改用 BigText 预览
- ESP32 托管页同步：消息显示 `⏳ 等待应答 / ⚠ 失败原因`，顶部提示待应答条数
- 修复：代码块段落漏设文本（只显示前后文字、代码块空白）
- 版本号 0.11.0（versionCode 15）

## 0.10.0 — 2026-11-01

- **消息页升级为人机对话程序（框架版）**
  - 消息模型 v2：`role/status/conv/reply_to`；老记录惰性兼容（不回写文件）
  - 投递状态机：`pending 等待应答` → `sent 已回复` / `error 失败可重试`；
    中继模式 15 分钟超时，其他模式按 `timeout_s`（默认 120 秒）；打开页面清理僵尸 pending
  - 应答引擎可插拔：**中继**（助手通过 HTTP 回复，默认）/ **OpenAI 兼容** / **AstrBot 本机模型**
  - 界面：状态徽标、时间戳、「助手正在输入…」、失败点按重试、长按重发、⚙ 应答设置、
    顶栏显示当前应答模式与等待条数、空态引导
- 新增接口：`/chat/retry?id=`、`/chat/config`（读/改应答配置，key 打码）、`/chat/brain`
- `/chat` 增加 `pending` 计数；`/chat/send` 支持 `reply_to`（助手回复时把对应消息置为已回复）
- 版本号 0.10.0（versionCode 14）

## 0.9.0 — 2026-11-01

- 供「托管界面」使用的新接口：
  - `GET /status`：电量/内存/存储/运行时长/IP/服务端口聚合
  - `GET /token?token=`：NapCat WebUI token（60 秒缓存）
- 所有响应加 CORS 头（`Access-Control-Allow-Origin: *`）并支持 `OPTIONS` 预检，
  浏览器里由 ESP32 托管的网页可以直接读取手机接口
- 版本号 0.9.0（versionCode 13）

## 0.8.0 — 2026-11-01

- 主屏显示当前 **NapCat WebUI Token**（从容器 `config/webui.json` 读取，60 秒刷新一次）：
  点按复制到剪贴板，长按直接打开带 token 的 WebUI 地址
- 修复 Termux 桥接的临时文件冲突：并发请求（如日志页轮询 + 手动命令）不再互相串输出
- 修复日志页退出后仍继续轮询的问题
- 版本号 0.8.0（versionCode 12）

## 0.7.2 — 2026-11-01

- 日志页首屏体验：打开/切换标签立即显示「正在读取日志…」，并先用会话内缓存渲染上次内容
- 版本号 0.7.2（versionCode 11）

## 0.7.1 — 2026-11-01

- 修复日志页：内容里出现 "No such file" 字样时被误报为「读取失败」
- 日志页去除 ANSI 颜色控制码，显示更干净
- 版本号 0.7.1（versionCode 10）

## 0.7.0 — 2026-11-01

- 新增**服务日志页**：切换 AstrBot / NapCat 日志尾部（3 秒自动刷新），
  一键启动 / 重启（含二次确认与防抖），显示服务运行状态
- 新增**消息页**：助手可远程发消息到手机（系统通知 + 未读数），用户可在页面回复
- 新增 HTTP 接口：`/logs`、`/chat`、`/chat/send`、`/svc`
- 主屏新增「日志」「消息」入口；主屏内容改为可滚动
- 一并修复 0.6.1 的明文 HTTP 限制（`usesCleartextTraffic`），ESP32 状态恢复正常
- 版本号 0.7.0（versionCode 9）

## 0.6.1 — 2026-11-01

- 修复：targetSdk 31 默认禁止明文 HTTP，导致主屏 ESP32 状态一直显示「离线」；
  增加 `android:usesCleartextTraffic="true"`

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
