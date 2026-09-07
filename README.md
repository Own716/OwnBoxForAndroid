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

**OwnBox for Android** 是一款基于 Sing-box 官方原生核心深度定制打造的 Android 通用网络代理客户端。集合全协议栈支持、自定义桌面图标切换 (19 款质感图标，含 4 款原神角色图标)、TCP Ping 极速真连测试、常用应用分流一键预设、单节点独立测速、并发拨号、双网络加速、WebDAV 云备份同步、控制中心四叶草快捷磁贴与桌面小组件等丰富功能，兼具极速连接、低耗电量与优雅简洁的用户界面。

---

## 📱 核心功能与软件特色 / Key Features

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

#### 🌐 3. 智能网络路由与规则分流
* **常用应用分流一键预设**：一键配置国内应用白名单与海外应用走代理模板；
* **分组与多订阅独立管理**：支持分组独立禁用与启用；
* **网络与 DNS 诊断面板**：Fake-IP 与 DNS 泄漏检测；
* **双网络加速 & 并发拨号**：移动蜂窝与 Wi-Fi 双通道并发传输，Happy Eyeballs 极速建连；
* **严格路由与防泄漏机制**：支持 RuleSet、GeoSite、GeoIP 路由规则，TUN 模式杜绝 DNS 泄漏。

#### 🎨 4. 个性化视觉与系统级深度适配
* **修改应用图标 (Change icon)**：内置 19 款高颜值桌面图标随心切换（含 4 款原神角色图标）；
* **控制中心四叶草磁贴 (Quick Settings Tile)**：彻底修复白圈问题，开/关状态高对比度清晰大图标显示；
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

## 📥 发行版下载 / Downloads

请前往官方发行版页面获取经过正式签名的全架构 APK 安装包：

👉 **[前往 GitHub Releases 官方发布页面](https://github.com/Own716/OwnBoxForAndroid/releases)**

| 架构 / 平台 | 适用设备 | 文件名 | 官方下载直链 |
| :--- | :--- | :--- | :--- |
| **ARM64 (强力推荐)** | **绝大多数现代安卓手机 (一加、小米、华为、OPPO、vivo、三星、荣耀等)** | `Ownbox-2.3.5-arm64-v8a-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-arm64-v8a-release.apk) |
| **ARMv7** | 较老旧的 32 位安卓机型 | `Ownbox-2.3.5-armeabi-v7a-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-armeabi-v7a-release.apk) |
| **x86_64** | 电脑 64 位安卓模拟器、ChromeOS | `Ownbox-2.3.5-x86_64-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-x86_64-release.apk) |
| **x86** | 电脑 32 位安卓模拟器 | `Ownbox-2.3.5-x86-release.apk` | [**📥 点击直接下载**](https://github.com/Own716/OwnBoxForAndroid/releases/download/v2.3.5/Ownbox-2.3.5-x86-release.apk) |

---

### 📦 安装包架构说明 / Architecture Notes

* **ARM64 (v8a)**：适用于绝大多数主流 64 位安卓手机与平板设备；
* **ARMeabi-v7a**：适用于较老款的 32 位安卓设备或部分电视盒子；
* **x86_64 / x86**：适用于主流 PC 电脑安卓模拟器。
