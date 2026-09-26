# OwnBox for Android v2.8.15 预览版 (v2.8.15-preview)

* 侧边栏「Sing-box 仪表盘」全面支持自定义面板链接与主流预设一键切换：
  - 深度适配全新现代轻量面板 Zashboard (`https://board.zash.run.place/`)，开箱即用，界面视觉精致现代
  - 内置快捷面板预设选择：Zashboard（现代推荐）、内置 Yacd（官方离线轻量）、Metacubexd（专业完整版）及自定义 URL
  - 启用 WebView 混合内容放行（Mixed Content Always Allow），彻底解决 HTTPS 外部面板访问本机 HTTP Clash API（127.0.0.1:9090）被系统拦截的问题
  - 智能补全后端连接参数（自动传递 `hostname=127.0.0.1&port=9090`），实现免配置零阻碍即刻直连
  - 工具栏新增一键刷新按钮与当前活跃面板状态副标题指示，支持随时切换面板与一键恢复默认
* 递增版本号至 2.8.15 预览版（versionCode 318），支持无损覆盖升级安装
