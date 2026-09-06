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

**OwnBox for Android** 是一款基于 Sing-box 官方最新原生核心（v1.13.16）深度定制打造的 Android 通用网络代理客户端。集合全协议栈支持、TCP Ping 极速真连测试、内核级自动优选最低延迟节点、常用应用分流一键预设、单节点独立测速、并发拨号、双网络加速、WebDAV 云备份同步与控制中心专属快捷磁贴等丰富功能，兼具极速连接、低耗电量与优雅简洁的用户界面。

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

## 🆕 v2.3.5 版本更新说明（相较于 v2.3.2）

### 🌟 新增功能
1. **自动优选最低延迟节点 (URL-Test 智能自动切换)**：
   - 主页右上角三点菜单及设置中集成「自动优选最低延迟节点」开关；
   - 深度联动 sing-box 内核的 `urltest` 原生负载均衡与出站组调度，客户端自动对本组所有节点进行健康巡检与延迟监测，所有网络请求始终自动走时延最低的最优节点；
   - 当首选节点遭遇网络波动、丢包或故障时，内核毫秒级无感知自动切换至下一可用节点，断线不裸奔，彻底免去手动频繁测速切换节点的繁琐操作。

2. **常用应用分流一键预设 (国内应用直连 / 海外应用代理)**：
   - 「分流规则」页面菜单新增**「分流规则一键预设」**，可一键导入预置模板：
     - **预设：常用国内应用直连白名单**：一键将微信、QQ、支付宝、淘宝、京东、抖音、哔哩哔哩、美团、拼多多、网易云音乐、高德地图、百度地图等国内头部 App 加入 Direct 直连规则，国内应用流量不走代理，杜绝流量偷跑与推送延迟；
     - **预设：常用海外应用走代理**：一键将 Telegram、YouTube、Twitter (X)、Chrome、ChatGPT、Discord、WhatsApp、TikTok、Netflix、Spotify 等海外主流 App 统一加入 Proxy 代理规则；
   - 「分应用代理」页面同步支持一键勾选“国内常用应用”与“海外常用应用”快捷操作。

### 🛠️ 体验修复与优化
1. **系统 MONET 动态取色功能深度修复**：
   - 彻底修复此前在设置中开启“使用系统 MONET 动态取色”后无反应、依然停留在原设置颜色的问题；
   - 开启后即刻跟随 Android 12+ 系统壁纸色彩动态重绘界面；在 Activity 基类加入感知刷新机制，从设置返回主界面即时生效，无需重启应用。

2. **首次安装默认主题颜色调整为清新「绿色」**：
   - 首次下载安装或清除数据打开软件后，默认主题颜色由原来的深蓝色修改为清新自然的**生机绿 (Green)**，视觉体验更舒适护眼。

3. **关于页面（About）上游来源文本精确修正**：
   - 关于页面底部“上游仓库来源 (Fork)”名称正式修正为：`Throne for Android `。

---

## ✨ 软件核心特性与全量功能总览 / Features

### 🚀 1. 现代化协议栈全支持
* **下一代高速网络协议**：VLESS XHTTP (SplitHTTP)、Juicity、Snell (v1-v6)、ShadowsocksR (SSR)、Hysteria 1 / Hysteria 2、TUIC (v5)；
* **主流代理协议**：VLESS、VMess、Trojan、Shadowsocks (支持 SS-2022 及全部 AEAD 加密算法)、SOCKS5、HTTP(S)、SSH；
* **全新 WireGuard Endpoint 架构**：采用官方最新 WireGuard 端点设计，彻底解决多节点并发运行冲突；
* **自由链式代理 (Proxy Chain)**：支持将任意多个节点组合为前后置跳板链，保护真实访问 IP。

### ⚡ 2. 多维度极速测速体系
* **主页整组 TCP Ping 快速测试**：位于主界面右上角菜单，纯 TCP 3 次握手直接探测链路物理往返时延（RTT），秒出测试结果且极度省电，配合底层防回环保护在连接前后均能准确测量；
* **单节点独立精确测速**：每个节点卡片右侧专属“3 个点”菜单，一键独立测速，秒级呈现上行速率、下行速率与延迟；
* **多样化测速模式**：支持同时测下载和上传、仅测下载、仅测上传以及极速简单下载测试；
* **测试结果智能排序**：支持按延迟高低一键排序，并可一键清理不可用或超时的失效节点。

### 🌐 3. 智能网络路由与自动化调度
* **自动优选最低延迟节点**：内核级原生 URL-Test 自动负载调度，永远自动连接最快节点；
* **常用应用分流一键预设**：一键配置国内应用白名单与海外应用走代理模板；
* **双网络加速**：蜂窝移动数据与 Wi-Fi 双通道并发传输，显著提升复杂弱网下的网络稳定性；
* **并发拨号 (Happy Eyeballs)**：多路径并发发起网络握手，大幅缩短首包建连时延；
* **严格路由与防泄漏机制**：支持规则集（RuleSet）、GeoSite、GeoIP 路由规则，搭配 TUN 模式（支持 gVisor、System 及 Mixed 虚拟栈），杜绝 DNS 泄漏与旁路绕过。

### 🎨 4. 个性化视觉与系统级深度适配
* **控制中心四叶草磁贴 (Quick Settings Tile)**：完美契合 ColorOS / Android 系统着色规范，未开启状态暗灰圆底显示纯白四叶草，已开启状态高亮圆底显示亮蓝四叶草；
* **MONET 动态取色即时生效**：完美融入 Android 12+ 动态壁纸色彩生态，跟随壁纸实时取色变色；
* **生机绿默认主题与 20+ 款 Material 配色**：开箱即享清新护眼绿色，支持深色暗夜模式与 AMOLED 纯黑极致省电；
* **可莉专属二次元图标**：高清纯白自适应二次元萌系图标；
* **多语言随心切换**：应用内独立语言选择，支持简体中文、繁体中文、英文。

### ☁️ 5. 云端备份同步与桌面端跨平台互通
* **WebDAV 云端同步**：一键备份节点、分组与路由规则至坚果云、Nextcloud 等私有 WebDAV 服务器，并支持随时一键拉取恢复；
* **桌面端互通**：完美支持直接导入 Throne 桌面版 `.thrbackup` 备份文件；
* **全格式剪贴板一键导入**：智能识别各种常见单节点链接与订阅链接。

---

## 💬 交流与反馈 / Feedback

* 📢 **Telegram 频道**：[@KarenOwn](https://t.me/KarenOwn)
* 🐛 **问题反馈与建议**：[GitHub Issues](https://github.com/Own716/OwnBoxForAndroid/issues)
* 📦 **源代码仓库**：[Own716/OwnBoxForAndroid](https://github.com/Own716/OwnBoxForAndroid)

---

## 📜 开源协议 / License

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源协议。

