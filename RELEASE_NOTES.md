# OwnBox v2.7.7 正式版 (v2.7.7)

* 【负载均衡插槽】在节点名称与协议正中间插入带 Marquee 走马灯平滑滚动动效的节点详细参数（IP/域名:端口 + [订阅分组]），杜绝省略号截断与多余悬空字符
* 【容差配置开放】在负载均衡设置中开放切换容差 (Tolerance) 与双单位 (ms / s) 真实换算配置，兼顾灵敏度与防抖动
* 【断流根治】彻底根除 leastPing / URLTest 频繁断流 Bug，关闭强制中断已有连接（interrupt_exist_connections: false）并注入动态容差
* 【会话粘性】强化 LoadBalance 出站基于目标哈希（Consistent Hash）的稳定路由与会话粘性保持，防止同一目标流量在不同节点间反复横跳

