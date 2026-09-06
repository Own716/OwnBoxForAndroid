# Ownbox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/qinwenjie716-qwj/OwnBoxForAndroid)](https://github.com/qinwenjie716-qwj/OwnBoxForAndroid/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

基于 sing-box 内核的 Android 通用代理软件，定制增强版。

---

## 📱 功能亮点 / Features

除支持常规的代理协议外，Ownbox 融入了多项高级增强特性：

1. **双网络加速（WiFi + 移动数据并发）**  
   支持同时保持 WiFi 与移动网络连接并利用双通道，提升复杂网络环境下的网络吞吐与连接稳定性。
2. **纯 TUN 入站（禁用混合代理端口）**  
   提供纯粹的系统级 TUN 入站选项，不在本地开放额外的 Socks/HTTP 代理监听端口，防止局域网端口探测与应用泄漏。
3. **订阅节点测速模式（Speed Test）**  
   在节点列表界面支持一键节点下行/上行速率测试，支持自定义测速超时与测速下载地址。
4. **HEVTUN 内核协议栈支持**  
   在 TUN 实现选项中新增 `HEVTUN`（基于 hev-socks5-tunnel 的高性能轻量级协议栈）。
5. **并发拨号（Parallel Dialing）**  
   并发尝试建立连接，大幅缩短首包握手延迟。
6. **严格路由（Strict Route）**  
   防止流量绕过 TUN 接口造成直连泄露，保护数据隐私。

---

## 📥 下载指引 / Downloads

请前往仓库右侧的 **[Releases (发行版)](https://github.com/qinwenjie716-qwj/OwnBoxForAndroid/releases)** 页面下载最新版本的安装包（`.apk` 文件）。

### 各版本 APK 选择指南：

| 文件标识 | 适用设备 | 推荐说明 |
|---|---|---|
| `arm64-v8a.apk` | **现代主流安卓手机** | **绝大多数用户推荐下载此版本**（骁龙、联发科、天玑、麒麟等 64 位手机） |
| `universal.apk` | **全设备通用包** | 包含所有 CPU 架构库，若不清楚自己手机型号，下载此包即可正常安装运行 |
| `armeabi-v7a.apk` | 老款 32 位手机 | 适合 2016 年前生产或配置较低的老旧安卓设备 |
| `x86_64.apk` | 电脑模拟器 / PC 平板 | 适合在 Windows/Mac 上的安卓模拟器（如 MuMu、雷电、逍遥等） |

---

## 📲 安装与使用说明 / How to Install & Use

1. **下载安装包**：在手机浏览器中打开 [Releases 页面](https://github.com/qinwenjie716-qwj/OwnBoxForAndroid/releases)，选择对应架构的 `.apk` 文件下载。
2. **允许安装**：在系统提示“允许安装未知来源应用”时勾选允许。
3. **导入节点/订阅**：打开 Ownbox，点击右上角 `+` 号，选择从剪贴板导入订阅链接或扫描二维码。
4. **节点测速**：在节点列表点击右上角菜单，选择 **“节点测速”**，即可测试各节点的实际下载带宽速度。
5. **开启双网络加速**：在设置（Settings）-> 路由设置中开启 **“双网络加速”**（需确保系统同时开启了 WiFi 和蜂窝移动数据）。
6. **启动代理**：点击右下角浮动操作按钮启动 VPN 代理服务。

---

## 🛠 支持的代理协议 / Supported Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess / VLESS
* Trojan
* AnyTLS / ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard

---

## 📄 开源许可 / License

本项目遵循 GPL-3.0 开源许可协议。

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
