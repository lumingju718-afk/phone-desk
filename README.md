# 服务器桌面 · phone-desk

[![build](https://github.com/lumingju718-afk/phone-desk/actions/workflows/build.yml/badge.svg)](https://github.com/lumingju718-afk/phone-desk/actions/workflows/build.yml)

把一台闲置 Android 手机变成「服务器节点」的启动器（桌面）应用：主屏直接显示服务器状态，内置终端，并对外提供一套局域网 HTTP 控制接口。

当前版本：**0.9.0**（versionCode 13）

## 功能

| 功能 | 说明 |
|---|---|
| 状态面板 | 时间/日期、电量（% · 充电状态 · 温度）、内存、存储、运行时长、局域网 IP |
| 服务探活 | AstrBot（6185）、NapCat（6099）、SSH（8022）端口在线/离线 |
| ESP32 联动 | 拉取 `http://192.168.1.13/status`，显示充电/电量/守卫判定 |
| 应用内终端 | 输入命令即时执行，滚动输出；命令以应用自身 UID 运行 |
| 远程控制端 | 前台服务监听 `8089`，提供 `/ping`、`/exec`、`/termux`、`/ui/*` |
| **UI 控制（无障碍）** | 远程点击/滑动/长按、返回/主页/多任务、按文字点击、填文本、读节点树、截图 |
| Termux 桥接 | 通过 `RUN_COMMAND` 把命令交给 Termux 执行，借用其完整工具链（python/ssh/git…），结果经 `curl` 回调回传 |
| 开机自启 | `BOOT_COMPLETED` 接收器 + `START_STICKY` |
| 默认桌面 | 声明 HOME 意图；未设为默认时主屏底部有引导按钮 |
| 应用抽屉 | 长按空白处或点「应用」查看全部已安装应用 |
| **服务日志页** | 切换查看 AstrBot / NapCat 日志尾部（自动刷新），可**启动/重启**对应服务 |
| **WebUI Token** | 主屏直接显示 NapCat WebUI 当前 token；点按复制，长按打开带 token 的 WebUI |
| **消息页** | 助手远程发消息到手机（带通知），用户也可在页面回复；主屏显示未读数 |

## 架构

```
MainActivity        主屏：状态面板 + 快捷按钮 + 启动后台服务
TerminalActivity    终端页：命令输入/输出 + 远程地址提示
LogsActivity        日志页：AstrBot/NapCat 日志 + 启动/重启按钮
ChatActivity        消息页：助手↔用户 消息气泡 + 通知
ChatStore           消息 JSONL 存储 / 未读数 / 通知
SvcControl          NapCat/AstrBot 启停命令 + 端口探活
ExecService         前台服务（常驻通知），持有 HttpExecServer
HttpExecServer      ServerSocket 实现的极简 HTTP：/ping /exec /termux /ui/* /callback
Shell               以应用 UID 执行 /system/bin/sh
TermuxRunner        走 Termux RUN_COMMAND，结果经回调返回
DeskAccessibility   UI 自动化：手势/节点树/截图（需用户开启无障碍）
BootReceiver        开机拉起 ExecService
```

数据流（Termux 桥接）：

```
HTTP /termux?cmd=...
  → TermuxRunner 生成 id，startService(com.termux/.app.RunCommandService)
  → Termux 里 bash -c "<cmd> > tmp; curl POST /callback?id=..."
  → HttpExecServer 把结果写入 PENDING[id]
  → TermuxRunner 取回并作为 HTTP 响应返回
```

## 构建

依赖本机 Android 命令行工具链（无 Gradle）：

| 组件 | 路径 |
|---|---|
| JDK 17 | `E:\android-toolchain\jdk` |
| build-tools 34 | `E:\android-toolchain\build-tools` |
| android.jar | `E:\android-toolchain\android-sdk\platforms\android-34\android.jar` |

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

流程：`aapt2 compile → aapt2 link → javac → d8 → 打包 classes.dex → zipalign → apksigner`，
产物输出到 `dist/phone-desk.apk`。

### 签名

- 口令与密钥路径写在 `signing.properties`（**已被 .gitignore 排除，不要提交**）。
- 文件不存在时，构建脚本会自动生成随机口令和新密钥，并写入该文件。
- ⚠️ 换成新密钥后打的包**无法覆盖安装**旧密钥签名的版本。

### Linux / CI

`build.sh` 是同一套流程的 bash 实现，供 GitHub Actions 与 Linux/macOS 使用：

```bash
bash build.sh
```

## CI 自动构建

`.github/workflows/build.yml`：每次 push 到 `main`、提 PR、或打 `v*` 标签时自动构建：

- 使用 temurin JDK 17 + runner 自带的 Android SDK（缺 build-tools 34 / platform 34 会先 sdkmanager 安装）
- 产物以 `phone-desk-apk` 上传为构建产物
- 打标签时自动把 APK 附加到对应的 GitHub Release
- CI 使用一次性随机签名密钥，仅用于验证可构建，**不能覆盖安装手机上的正式版本**

## 安装

1. 把 `dist/phone-desk.apk` 推到手机（`scp ... /sdcard/Download/`）
2. 手机上用文件管理器点开安装
3. 打开一次应用（会弹出常驻通知「远程终端已开启」）
4. 按提示授予：
   - `com.termux.permission.RUN_COMMAND`（Termux 桥接必需，系统弹窗点允许）
   - 「所有文件访问」（可选，便于读写共享存储）
5. **开启无障碍**（UI 控制必需）：主屏底部点「无障碍：未开启」或进终端页点「开启无障碍」，
   在「设置 → 辅助功能 → 已下载服务」里勾选「服务器桌面」
6. 需要替换默认桌面时，点主屏底部「设为默认桌面」

## HTTP API

服务监听 `0.0.0.0:8089`，仅局域网可访问。默认 token：`dshdesk1`（见 `ExecService.ensureToken`）。

| 接口 | 说明 |
|---|---|
| `GET /ping` | 探活，返回 `{"ok":true,"service":"desk-exec"}` |
| `GET /status` | 状态聚合：电量/内存/存储/运行时长/IP/服务端口（给 ESP32 网页用） |
| `GET /token?token=` | NapCat WebUI token（60 秒缓存） |
| `GET /exec?token=&cmd=` | 以应用 UID 执行命令 |
| `GET /termux?token=&cmd=` | 交给 Termux 执行（需 RUN_COMMAND 权限） |
| `POST /callback?token=&id=&code=` | Termux 回传结果的内部接口 |
| `GET /ui/on` | 无障碍服务是否已开启 |
| `GET /ui/shot?w=1080` | 截图，返回 `{mime:"image/jpeg", b64:"..."}` |
| `GET /ui/dump?depth=20` | 当前界面节点树（text/desc/id/bounds/clickable/editable） |
| `GET /ui/tap?x=&y=` | 点击坐标 |
| `GET /ui/long?x=&y=&ms=600` | 长按 |
| `GET /ui/swipe?x1=&y1=&x2=&y2=&ms=300` | 滑动 |
| `GET /ui/global?a=back\|home\|recents\|notif\|quick` | 全局操作 |
| `GET /ui/text?text=` | 向当前输入框填入文本 |
| `GET /ui/click?text=`（或 `id=`） | 按文字/资源 id 点击 |
| `GET /logs?token=&which=napcat\|astrbot&lines=200` | 读取服务日志尾部（≤500 行） |
| `GET /chat?token=&since=` | 读取消息列表与未读数 |
| `GET\|POST /chat/send?token=&from=ai\|user&text=` | 发送一条消息（`from=ai` 会触发手机通知） |
| `GET /svc?token=&name=napcat\|astrbot&action=start\|stop\|restart` | 启动/停止/重启服务 |

示例：

```bash
curl -G http://192.168.1.11:8089/exec --data-urlencode "token=dshdesk1" --data-urlencode "cmd=id"
curl -G http://192.168.1.11:8089/termux --data-urlencode "token=dshdesk1" --data-urlencode "cmd=whoami"
curl -G http://192.168.1.11:8089/ui/on --data-urlencode "token=dshdesk1"
curl -G http://192.168.1.11:8089/ui/tap --data-urlencode "token=dshdesk1" -d "x=540" -d "y=1200"
```

## 安全说明

- 接口只监听局域网，token 是明文常量，请勿在不可信网络使用。
- 命令以应用自身权限运行（普通应用 UID），**没有 root**。
- 开启无障碍后，持有 token 的客户端可以点击、输入、读屏、截图——权限很大，请只在自己可控的网络里使用。
- 仍然做不到：静默安装 APK、读取其他应用私有数据、修改系统级设置、绕过安全窗口。

## 目录结构

```
app/AndroidManifest.xml
app/res/values/strings.xml
app/src/com/dsh/desk/*.java
build.ps1
.gitignore / .gitattributes
README.md / CHANGELOG.md
```

## ESP32 托管界面

`E:\work\charge_led` 固件除了原来的充电灯页，还提供一页网页版服务器桌面：`http://<ESP32_IP>/desk`。
页面本身由 ESP32 静态托管（存在 flash 里），数据和操作由浏览器**直连手机** `8089`：
`/status`、`/logs`、`/chat`、`/chat/send`、`/token`、`/svc`。
手机端已加 CORS 头（`Access-Control-Allow-Origin: *`），因此无需 ESP32 代理。
前提：手机安装 ≥ 0.9.0 的 App，且 ESP32 与手机在同一局域网。

## 已知限制

- Termux 桥接要求 Termux 开启 `allow-external-apps=true`，且应用被授予 `RUN_COMMAND` 权限。- `/exec` 为应用 UID，读不到 Termux 私有目录；需要跨 UID 的操作走 `/termux`。
- 状态面板默认探测 `192.168.1.13` 的 ESP32 灯，换网络需改 `MainActivity.ESP_URL`。
- UI 控制要求手机**已解锁并亮屏**；无障碍不能点亮屏幕。
- 银行/密码等安全窗口：截图返回空、节点树为空，属系统限制。
- 截图接口约 1 次/秒，连续调用会失败。
- 日志页要求 Termux 桥接可用；服务从未启动过时日志文件不存在，页面会给出提示。
- 消息记录保存在应用私有目录，卸载即清空；手机侧靠系统通知提示新消息。
- NapCat 重启后若需要扫码，日志页会显示二维码解码 URL，需要手机扫码完成登录。

## 版本管理

- 远程仓库：`https://github.com/lumingju718-afk/phone-desk`（公开）
- 发版：`git tag -a v0.7.0 -m "..."` 后 `git push origin main --tags`
- 若本机访问 GitHub 需要代理：

  ```powershell
  git config --global http.https://github.com.proxy http://127.0.0.1:7890
  $env:HTTPS_PROXY='http://127.0.0.1:7890'   # gh CLI 需要环境变量
  ```

