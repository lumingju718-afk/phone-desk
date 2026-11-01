# 服务器桌面 · phone-desk

把一台闲置 Android 手机变成「服务器节点」的启动器（桌面）应用：主屏直接显示服务器状态，内置终端，并对外提供一套局域网 HTTP 控制接口。

当前版本：**0.5.0**（versionCode 5）

## 功能

| 功能 | 说明 |
|---|---|
| 状态面板 | 时间/日期、电量（% · 充电状态 · 温度）、内存、存储、运行时长、局域网 IP |
| 服务探活 | AstrBot（6185）、NapCat（6099）、SSH（8022）端口在线/离线 |
| ESP32 联动 | 拉取 `http://192.168.1.13/status`，显示充电/电量/守卫判定 |
| 应用内终端 | 输入命令即时执行，滚动输出；命令以应用自身 UID 运行 |
| 远程控制端 | 前台服务监听 `8089`，提供 `/ping`、`/exec`、`/termux` |
| Termux 桥接 | 通过 `RUN_COMMAND` 把命令交给 Termux 执行，借用其完整工具链（python/ssh/git…），结果经 `curl` 回调回传 |
| 开机自启 | `BOOT_COMPLETED` 接收器 + `START_STICKY` |
| 默认桌面 | 声明 HOME 意图；未设为默认时主屏底部有引导按钮 |
| 应用抽屉 | 长按空白处或点「应用」查看全部已安装应用 |

## 架构

```
MainActivity        主屏：状态面板 + 快捷按钮 + 启动后台服务
TerminalActivity    终端页：命令输入/输出 + 远程地址提示
ExecService         前台服务（常驻通知），持有 HttpExecServer
HttpExecServer      ServerSocket 实现的极简 HTTP：/ping /exec /termux /callback
Shell               以应用 UID 执行 /system/bin/sh
TermuxRunner        走 Termux RUN_COMMAND，结果经回调返回
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

## 安装

1. 把 `dist/phone-desk.apk` 推到手机（`scp ... /sdcard/Download/`）
2. 手机上用文件管理器点开安装
3. 打开一次应用（会弹出常驻通知「远程终端已开启」）
4. 按提示授予：
   - `com.termux.permission.RUN_COMMAND`（Termux 桥接必需，系统弹窗点允许）
   - 「所有文件访问」（可选，便于读写共享存储）
5. 需要替换默认桌面时，点主屏底部「设为默认桌面」

## HTTP API

服务监听 `0.0.0.0:8089`，仅局域网可访问。默认 token：`dshdesk1`（见 `ExecService.ensureToken`）。

| 接口 | 说明 |
|---|---|
| `GET /ping` | 探活，返回 `{"ok":true,"service":"desk-exec"}` |
| `GET /exec?token=&cmd=` | 以应用 UID 执行命令 |
| `GET /termux?token=&cmd=` | 交给 Termux 执行（需 RUN_COMMAND 权限） |
| `POST /callback?token=&id=&code=` | Termux 回传结果的内部接口 |

示例：

```bash
curl -G http://192.168.1.11:8089/exec --data-urlencode "token=dshdesk1" --data-urlencode "cmd=id"
curl -G http://192.168.1.11:8089/termux --data-urlencode "token=dshdesk1" --data-urlencode "cmd=whoami"
```

## 安全说明

- 接口只监听局域网，但 token 是明文常量，请勿在不可信网络使用。
- 命令以应用自身权限运行（普通应用 UID），**没有 root**。
- 因此无法：静默安装 APK、模拟点击/输入、读取其他应用私有数据、修改系统级设置。

## 目录结构

```
app/AndroidManifest.xml
app/res/values/strings.xml
app/src/com/dsh/desk/*.java
build.ps1
.gitignore / .gitattributes
README.md / CHANGELOG.md
```

## 已知限制

- Termux 桥接要求 Termux 开启 `allow-external-apps=true`，且应用被授予 `RUN_COMMAND` 权限。
- `/exec` 为应用 UID，读不到 Termux 私有目录；需要跨 UID 的操作走 `/termux`。
- 状态面板默认探测 `192.168.1.13` 的 ESP32 灯，换网络需改 `MainActivity.ESP_URL`。

## 版本管理

- 远程仓库：`https://github.com/lumingju718-afk/phone-desk`（私有）
- 发版：`git tag -a v0.5.1 -m "..."` 后 `git push origin main --tags`
- 若本机访问 GitHub 需要代理：

  ```powershell
  git config --global http.https://github.com.proxy http://127.0.0.1:7890
  $env:HTTPS_PROXY='http://127.0.0.1:7890'   # gh CLI 需要环境变量
  ```

