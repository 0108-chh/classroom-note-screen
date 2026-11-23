# 班级便签屏

> 老师在手机上打一行字，教室里的智能屏 / 电脑立刻弹出一张便签纸，浮在所有窗口最上面。被控端常驻后台，只有人为关闭才会停。

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Python](https://img.shields.io/badge/Python-3.10%2B-blue.svg)](https://www.python.org/)
[![Android](https://img.shields.io/badge/Android-6.0%2B-3ddc84.svg)](#)

一个不用注册、不用服务器、不用内网穿透的远程便签方案：三端各自连公共 MQTT 节点，用设备号 + 密钥派生出只有这两台机器知道的信道。

```
 老师手机（控制端 APK）
        │  输入文字 → 发到公共中转节点
        ▼
   公网 MQTT 中转（EMQX / HiveMQ / Mosquitto，免注册）
        │  订阅各自信道
        ├──────────────▶ 教室 A：Windows 被控端 → 置顶便签窗
        └──────────────▶ 教室 B：Android 被控端 → 系统悬浮便签
```

同一套代码既能装在 Windows 电脑 / 智慧黑板上，也能装在 Android 智能屏上；老师一台手机可以绑定任意多台大屏，勾选后一次群发。

## 组成

| 目录 | 是什么 | 运行环境 |
|---|---|---|
| `windows/` | Windows 被控端（托盘常驻 + 置顶便签窗） | Windows 10/11 + WebView2 |
| `android-device/` | Android 被控端（悬浮窗 + 前台服务 + 开机自启） | Android 6.0+ |
| `android-remote/` | 老师手机控制端（扫码绑定、多设备群发、5 色便签） | Android 7.0+ |

构建产物不入库（`.exe` / `.apk` 见 [Releases](../../releases) 或自行打包）。

## 第一次配置（每台大屏一次）

### 1. 教室里的大屏

**Windows**：双击 `班级便签屏.exe`。窗口右上角显示绿点「通道正常」即已连上公网。界面里的二维码就是这台大屏的「门牌」，关掉窗口只会缩到右下角托盘，程序照常在跑。

**Android**：
1. 安装 APK，打开后按提示点「去授权」，允许**显示在其他应用上层**（悬浮窗权限）—— 不授权便签弹不出来。
2. 点「去设置」把电池优化设为**不限制**，否则系统会在息屏后掐掉后台。
3. 回到首页点「开启常驻接收」。通知栏出现一条常驻通知，说明服务在跑。

### 2. 老师手机绑定

1. 打开控制端，点「+ 添加设备」→「扫码绑定」，对准大屏上的二维码。
2. 识别后点「保存绑定」，列表里出现这台大屏，绿点表示在线。
3. 两台教室就重复两次；最多可绑定任意多台，默认全勾选、一起发。

## 日常使用

1. 手机首页输入内容（也可以用下面的快捷短语拼一句）。
2. 选便签颜色，勾选要发的教室。
3. 点「发送便签」。大屏上立刻弹出对应颜色的便签，内容一字不差。
4. 便签会一直停在屏幕上，老师可以再发一条覆盖，或在大屏上点 × 关掉；多条便签时出现 `1/2` 翻页按钮。

发送后手机会提示「已送达并显示」；若显示「其余设备可能离线」，说明那台大屏网络断了或服务没开。

## 常驻后台的边界（重要）

| 平台 | 什么情况下会停 |
|---|---|
| Windows | 托盘右键 → 退出，或面板点「退出程序」，或任务管理器结束进程，或关机 |
| Android | 通知栏点「停止接收」，或应用内点「停止接收」，或在系统设置里强行停止应用 |

- 关掉大屏上的窗口 / 界面、划掉最近任务、重启电脑手机，都**不会**中断：Android 端会开机自启（首页开关可关）；Windows 端需自行把 exe 放进「启动」文件夹。
- Windows 开机自启：`Win+R` 输入 `shell:startup`，把 exe 的快捷方式丢进去。
- 安卓被系统省电策略杀掉时，通知会消失；重新打开 App 点一次「开启常驻接收」即可。

## 从源码构建

### Windows 被控端

需要 Python 3.10+。

```bash
cd windows
pip install -r requirements.txt
python app/main.py          # 开发运行
python build_exe.py         # 打包单文件 -> dist/班级便签屏.exe
```

### Android 两端

需要 JDK 17、Android SDK 34、Gradle 8.7。首次构建前在两个模块下各写一个 `local.properties`：

```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
```

然后：

```bash
cd android-device && gradle assembleDebug
cd ../android-remote && gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

被控端还需要在系统里允许「悬浮窗」与「后台弹出界面」权限；控制端要能调起相机（zxing-android-embedded），两端 manifest 都已声明 `usesCleartextTraffic`（公共 MQTT 是明文 1883）。

## 通信协议

三端（`windows/app/pairing.py`、`android-device/…/Pairing.java`、`android-remote/…/Pairing.java`）必须逐字节一致：

```
channel = sha256("设备号:密钥").hex()[0:20]
主题     = ns1/<channel>/in      发便签
         │ ns1/<channel>/st      在线状态（retained + LWT）
         └ ns1/<channel>/ack     送达回执
配对串   = NS1|设备号|密钥|base32(设备名)     ← 二维码内容
```

- 4 个公共节点**并发连接**（`broker-cn.emqx.io`、`broker.emqx.io`、`broker.hivemq.com`、`test.mosquitto.org`，TCP 1883），任一条通即可用，界面上会显示「通道正常 · N 路」。
- 上线时往 `st` 发 retained 消息，客户端断开由 LWT 自动把状态刷成离线，所以手机端看到的绿点是实时的。
- `ack` 用于「已送达并显示」回执，收不到就提示可能离线。

## 安全与隐私

- 消息走**公共免费 MQTT 节点**，**未做端到端加密**；信道名由设备号 + 密钥哈希得出（不可枚举、不可猜），但没有服务端证书校验 —— 课堂提示语这类内容没问题，不要用来传敏感信息。
- 二维码里含密钥：不要贴到公开的地方，也不要截图发群里。
- 程序不做任何统计上报，不含第三方 SDK / 广告。
- 想更安全：把 `Bus` 里的节点换成自建 Mosquitto 并启用 TLS，同时换成强随机密钥。

## 常见问题

**Q：大屏显示「通道未连通 / 正在重试」**
公网 1883 端口被网络策略拦了。换个网络（比如手机热点）试试；学校网络如果屏蔽非常规端口，需要放行 TCP 1883 出站。

**Q：手机点发送提示「通道还没连上」**
等 3~5 秒，右上角变成「通道正常 · N 路」再发。刚打开 App 时正在并发连接 4 个节点。

**Q：便签能收到但看不到**
Android 大屏：十有八九是没给悬浮窗权限，或被「后台弹出界面」权限限制。设置 → 应用 → 班级便签屏 → 权限，打开悬浮窗。

**Q：两台大屏会互相收到吗**
不会。每台大屏的设备号 + 密钥派生出唯一信道，主题互不相通。

**Q：换电脑 / 重装 App 后要重新绑定吗**
会。设备身份存在本机（Windows 在 `%APPDATA%\NoteScreen\state.json`），换机或卸载重装就会变，重新扫一次码即可。

## 已知限制

- 公共节点是免费共享的，极端情况下可能限流或短暂不可用；四个节点同时连就是为了兜底。
- Android 端未在真机上做完整回归（悬浮窗、开机自启、前台服务在小部分定制 ROM 上表现可能不同），遇到问题欢迎提 Issue 并带上机型与系统版本。
- 便签只支持纯文本 + 5 种底色，不做富文本 / 图片。

## 贡献

欢迎 Issue / PR。改协议相关的代码时，**三端要一起改**，否则老版本客户端会收不到消息。

## License

[MIT](LICENSE)
