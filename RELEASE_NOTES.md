# OwnBox for Android v2.5.5 预览版 (v2.5.5-preview)

## 版本更新摘要（Release Notes）

* **订阅批量更新闪退修复 (Fragment Lifecycle)**：彻底修复在订阅分组列表点击“一键更新所有订阅”时，因用户离开页面或页面切换导致 Fragment 解除绑定（Detached）而抛出 `java.lang.IllegalStateException: Fragment not attached to an activity` 的致命闪退问题。
* **全局异步通知与生命周期防御性加固**：全面重构 Fragment 提示组件，新增 `safeSnackbar` 安全派发机制与全局活动回退兜底，针对所有后台并发任务、导入导出操作实施全方位生命周期安全保护，杜绝任何页面脱离导致的空指针与状态异常崩溃。
