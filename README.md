# OwnBox for Android

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" alt="OwnBox Logo">
  <br>
  <b>适用于 Android 的现代化通用代理工具链与网络调试客户端</b>
</p>

<p align="center">
  <a href="https://github.com/Own716/OwnBoxForAndroid/releases"><img src="https://img.shields.io/badge/Release-v2.3.5-blue.svg?style=flat-square" alt="Version"></a>
  <a href="https://android-arsenal.com/api?level=21"><img src="https://img.shields.io/badge/Android-5.0%2B%20(API%2021%2B)-brightgreen.svg?style=flat-square" alt="API"></a>
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPL--3.0-orange.svg?style=flat-square" alt="License"></a>
  <a href="https://t.me/KarenOwn"><img src="https://img.shields.io/badge/Telegram-@KarenOwn-2CA5E0.svg?logo=telegram&style=flat-square" alt="Telegram"></a>
</p>

---

## 📖 项目介绍 / Introduction

**OwnBox for Android** 是一款基于 Sing-box 官方最新原生核心（v1.13.16）深度定制打造的 Android 通用网络代理客户端。集合全协议栈支持、TCP Ping 极速真连测试、内核级自动优选最低延迟节点、常用应用分流一键预设、单节点独立测速、并发拨号、双网络加速、WebDAV 云备份同步、控制中心专属快捷磁贴与桌面小组件等丰富功能，兼具极速连接、低耗电量与优雅简洁的用户界面。

---

## 📥 最新发行版下载 / Downloads (v2.3.5)

请前往官方发行版页面获取经过正式签名的全架构 APK 安装包：

👉 **[前往 GitHub Releases 官方发布页面](https://github.com/Own716/OwnBoxForAndroid/releases/tag/v2.3.5)**

| 架构 / 平台 | 适用设备 | 文件名 | 官方下载直链 |
| :--- | :--- | :--- | :--- |
| **ARM64 (强力推荐)** | **绝大多数现代安卓手机 (一加、小米、华为、OPPO、vivo、三星、荣耀等)** | `Ownbox-2.3.5-arm64-v8a-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-arm64-v8a-release.apk) |
| **ARMv7** | 较老旧的 32 位安卓机型 | `Ownbox-2.3.5-armeabi-v7a-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-armeabi-v7a-release.apk) |
| **x86_64** | 电脑 64 位安卓模拟器、ChromeOS | `Ownbox-2.3.5-x86_64-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-x86_64-release.apk) |
| **x86** | 电脑 32 位安卓模拟器 | `Ownbox-2.3.5-x86-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-x86-release.apk) |

---

### 🆕 相较于 2.3.2 版本的更新与修复明细

#### 🌟 新增功能与深度增强
1. **自动优选最低延迟节点（URL-Test 完整修复与进阶参数）**：
   - 彻底修复此前开启后无响应的问题，完善主界面菜单与全局状态双向联动机制；
   - 支持在分组设置中深度暴露与配置 `urltest` 核心参数：健康检查 URL、测试间隔 (`interval`，默认 180s)、容差阈值 (`tolerance`，默认 50ms)、空闲挂起超时 (`idle_timeout`，默认 30m) 以及选优切换是否打断已有连接 (`interrupt_exist_connections`)；
   - 当节点出现波动、丢包或异常超时时，内核毫秒级无感知自动无缝漂移至下一最佳可用节点。

2. **控制中心四叶草磁贴 Logo 尺寸优化**：
   - 针对控制中心快捷磁贴四叶草 Logo 偏小的问题进行重新裁切与重绘，画布有效像素占比提升至 97%，居中放大 40%，与系统原生磁贴视觉尺寸完美协调对齐。

3. **桌面快捷开关小组件 (App Widget)**：
   - 新增桌面小组件支持，无需点进 App 即可在手机主屏幕上一键启停 VPN 代理，并实时呈现当前节点名称与连接状态。

4. **常用应用分流一键预设 (国内应用直连 / 海外应用代理)**：
   - 在「分流规则」页面菜单中提供「分流规则一键预设」：
     - **常用国内应用直连白名单**：一键注入微信、QQ、支付宝、淘宝、京东、抖音、哔哩哔哩、美团、拼多多、网易云音乐、高德地图、百度地图等 Direct 直连规则；
     - **常用海外应用走代理**：一键注入 Telegram、YouTube、Twitter/X、Chrome、ChatGPT、Discord、WhatsApp、TikTok、Netflix、Spotify 等 Proxy 代理规则；
   - 在「分应用代理」列表中同步支持一键快捷勾选国内与海外头部应用。

5. **REALITY 与 ECH 规范完整对齐**：
   - REALITY 完整支持 `tls.reality`（`public_key`, `short_id`, `server_name`）与客户端 `tls.utls.fingerprint` 模拟指纹（如 chrome）；
   - 对齐 sing-box 1.12+ ECH 标准库规范，剔除废弃的 `pq_signature_schemes_enabled`，保障 TLS 握手稳定性。

6. **Hysteria 2 端口跳跃解析修复**：
   - 彻底修复内核端口跳跃 `server_ports` 字段解析规范，支持短横线区间格式（如 `2080-3000`），确保 sing-box 1.12+ 内核不出现解析报错。

7. **AmneziaWG 智能兼容降级解析**：
   - 支持导入 `awg://` 链接与 AmneziaWG 配置文件，智能剥离非标混淆参数（`Jc/Jmin/S1/H1`）并以标准 WireGuard 字段稳定运行，自动在节点名称增加 `[AWG-Compat]` 标示。

8. **多订阅/分组独立启用与禁用开关**：
   - 分组卡片专属操作菜单新增“禁用/启用此分组”，被禁用的分组在界面半透明弱化显示，并在内核配置生成与自动优选中彻底排除。

9. **智能粘贴导入预览弹窗**：
   - 剪贴板识别到多个节点链接时，弹出清晰美观的协议与节点名称预览确认对话框，防止一次性误导入混乱列表。

10. **Fake-IP / DNS 泄漏实时检测面板**：
    - 在网络工具箱中新增 DNS 泄漏与 Fake-IP 映射实时检测，一目了然验证当前 DNS 解析环境。

11. **崩溃日志一键导出与本地诊断**：
    - 支持捕获应用与 sing-box 核心异常日志并一键导出至外置存储，极大方便技术排查。

12. **本地配置自动定时备份**：
    - 支持一键将节点、分组、分流规则与首选项完整打包存储在本地，并自动保留最近 5 份历史快照。

#### 🛠️ 体验修复与优化
1. **系统 MONET 动态取色功能深度修复**：
   - 修复在设置中开启“使用系统 MONET 动态取色”后无反应的问题；
   - 开启后即刻跟随 Android 12+ 系统壁纸色彩动态重绘界面，返回主界面即时生效。

2. **首次安装默认主题颜色调整为「绿色」**：
   - 默认主题颜色修改为清新自然的**生机绿 (Green)**，视觉体验更舒适护眼。

3. **关于页面（About）上游仓库来源名称精确修正**：
   - 关于页面底部“上游仓库来源 (Fork)”名称正式修正为：`Throne for Android `。

---

### 📱 2.3.5 全量功能与软件特色总览

#### 🚀 1. 现代化协议栈全协议支持
* **下一代高速网络协议**：VLESS XHTTP (SplitHTTP)、Juicity、Snell (v1-v6)、ShadowsocksR (SSR)、Hysteria 1 / Hysteria 2、TUIC (v5)；
* **主流代理协议**：VLESS、VMess、Trojan、Shadowsocks (支持 SS-2022 及全部 AEAD 加密算法)、SOCKS5、HTTP(S)、SSH；
* **REALITY & ECH 现代化安全传输**：TLS 流量完美伪装与客户端指纹模拟；
* **WireGuard / AmneziaWG 兼容架构**：标准 WireGuard 端点设计与 AWG 智能降级层；
* **自由链式代理 (Proxy Chain)**：支持将任意多个节点组合为前后置代理跳板链，保护真实访问 IP。

#### ⚡ 2. 多维度极速测速体系
* **主页整组 TCP Ping 快速测试**：位于主界面右上角菜单，纯 TCP 3 次握手直接探测链路物理往返时延（RTT），秒出测试结果且极低功耗；
* **单节点独立精确测速**：每个节点卡片右侧专属菜单，一键独立测速，秒级呈现上行速率、下行速率与延迟；
* **多样化测速模式**：支持同时测下载和上传、仅测下载、仅测上传以及极速简单下载测试；
* **测试结果智能排序**：支持按延迟高低一键排序，并可一键清理不可用或超时的失效节点。

#### 🌐 3. 智能网络路由与自动化调度
* **自动优选最低延迟节点**：内核级原生 URL-Test 自动负载调度，暴露超时与容差高级参数；
* **常用应用分流一键预设**：一键配置国内应用白名单与海外应用走代理模板；
* **分组与多订阅独立管理**：支持分组独立禁用与启用；
* **网络与 DNS 诊断面板**：Fake-IP 与 DNS 泄漏检测；
* **双网络加速 & 并发拨号**：移动蜂窝与 Wi-Fi 双通道并发传输，Happy Eyeballs 极速建连；
* **严格路由与防泄漏机制**：支持 RuleSet、GeoSite、GeoIP 路由规则，TUN 模式杜绝 DNS 泄漏。

#### 🎨 4. 个性化视觉与系统级深度适配
* **控制中心四叶草磁贴 (Quick Settings Tile)**：放大优化适配，开/关状态高对比度清晰显示；
* **桌面快捷小组件 (App Widget)**：主屏幕快速启停与状态展示；
* **MONET 动态取色即时生效**：完美融入 Android 12+ 动态壁纸色彩生态；
* **生机绿默认主题与 20+ 款 Material 配色**：清新护眼，支持暗夜与纯黑模式；
* **智能导入弹窗**：剪贴板批量节点预览确认；
* **多语言随心切换**：应用内支持简体中文、繁体中文、英文。

#### ☁️ 5. 数据安全与云端备份互通
* **本地定时快照**：自动备份与版本回溯，保留最近 5 份快照；
* **WebDAV 云端同步**：一键备份至坚果云、Nextcloud 等私有 WebDAV 服务器并随时恢复；
* **桌面端互通**：支持直接导入 Throne 桌面版 `.thrbackup` 备份文件；
* **崩溃日志导出**：一键导出诊断日志排查问题。

---

### 📥 官方安装包推荐

| 架构 / 平台 | 适用设备 | 推荐下载文件 |
| :--- | :--- | :--- |
| **ARM64 (强力推荐)** | **绝大多数现代安卓手机 (一加、小米、华为、OPPO、vivo、三星、荣耀等)** | `Ownbox-2.3.5-arm64-v8a-release.apk` |
| **ARMv7** | 较老旧的 32 位安卓机型 | `Ownbox-2.3.5-armeabi-v7a-release.apk` |
| **x86_64** | 电脑 64 位安卓模拟器、ChromeOS | `Ownbox-2.3.5-x86_64-release.apk` |
| **x86** | 电脑 32 位安卓模拟器 | `Ownbox-2.3.5-x86-release.apk` |

---

## 💬 交流与反馈 / Feedback

* 📢 **Telegram 频道**：[@KarenOwn](https://t.me/KarenOwn)
* 🐛 **问题反馈与建议**：[GitHub Issues](https://github.com/Own716/OwnBoxForAndroid/issues)
* 📦 **源代码仓库**：[Own716/OwnBoxForAndroid](https://github.com/Own716/OwnBoxForAndroid)

---

## 📜 开源协议 / License

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源协议。
