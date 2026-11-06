# 竞态模拟：验证 "boxPlatformInterfaceWrapper 单例被多 box 并发 Initialize 覆盖"
# 假设能否复现日志中的失败模式。
#
# 机制建模：
# - 每个测速 box：New 结束时 w.nm = 自己；经过 gap 窗口后 Start 回调触发
#   UpdateInterfaces(w.nm) —— 只刷新"当前最新 box"的 NetworkManager 缓存。
# - box X 的缓存被填充 <=> 存在某次回调发生时 X 仍是最新 box。
#   若另一 box 的 New 在 X 的 gap 窗口内完成，则 X 永远失去填充机会 -> 拨号秒报
#   "no available network interface"（selectInterfaces 返回空）。
# - 冷启动：DefaultNetworkListener 缓存未热时，Start 回调不触发；
#   系统 onAvailable 在 T_warm 时刻统一回调所有在途监听 -> 只有最新 box 受益。
#
# 对照：日志 9114654675789577133（热缓存场景）约 40 次测试中 ~6 次
# "no available network interface"（~15%），且批次头部集中失败（冷缓存）。

import random
import statistics

N_TESTS = 40
WORKERS = 5
ROUNDS = 4000


def simulate(warm_latency_ms: float) -> list[bool]:
    """返回每个测试是否失败（缓存未填充）。"""
    # 每个测试的时间参数
    t_new = [random.uniform(20, 80) for _ in range(N_TESTS)]   # buildConfig+box.New
    t_gap = [random.uniform(5, 40) for _ in range(N_TESTS)]    # launch+preStart+JNI+binder
    t_dial = [random.uniform(200, 1000) for _ in range(N_TESTS)]

    # 工人调度：每个工人串行取测试，记录 new_end / start_cb 时刻
    new_end = [0.0] * N_TESTS
    start_cb = [0.0] * N_TESTS
    worker_free = [0.0] * WORKERS
    for i in range(N_TESTS):
        w = min(range(WORKERS), key=lambda k: worker_free[k])
        t0 = worker_free[w]
        new_end[i] = t0 + t_new[i]
        start_cb[i] = new_end[i] + t_gap[i]
        worker_free[w] = start_cb[i]  # 回调后即拨号（失败 0ms / 成功 t_dial），先占位，后面修正

    # 需要按失败与否修正工人占用，迭代两轮近似（失败会提前释放工人）
    for _ in range(3):
        failed = compute_failed(new_end, start_cb, warm_latency_ms)
        worker_free = [0.0] * WORKERS
        for i in range(N_TESTS):
            w = min(range(WORKERS), key=lambda k: worker_free[k])
            t0 = worker_free[w]
            new_end[i] = t0 + t_new[i]
            start_cb[i] = new_end[i] + t_gap[i]
            worker_free[w] = start_cb[i] + (0.0 if failed[i] else t_dial[i])
    return compute_failed(new_end, start_cb, warm_latency_ms)


def compute_failed(new_end, start_cb, warm_latency_ms):
    n = len(new_end)
    failed = [False] * n
    for i in range(n):
        cb_time = max(start_cb[i], warm_latency_ms)  # 冷缓存时回调推迟到 warm
        # 回调时刻的"最新 box"：new_end 最大且 <= cb_time... 注意 new 可能晚于 cb
        latest = -1
        latest_t = -1.0
        for j in range(n):
            if new_end[j] <= cb_time and new_end[j] > latest_t:
                latest_t = new_end[j]
                latest = j
        # 自己不是最新 -> 自己的回调刷的是别人的缓存；
        # 且之后自己再也不会是最新 -> 缓存永远为空 -> 失败
        if latest != i:
            failed[i] = True
    return failed


def main():
    random.seed(42)

    # 场景 A：热缓存（app 已运行许久，DefaultNetworkListener 已有缓存）
    warm_rates = []
    for _ in range(ROUNDS):
        failed = simulate(warm_latency_ms=0.0)
        warm_rates.append(sum(failed) / N_TESTS)
    print(f"[热缓存] 平均失败率 {statistics.mean(warm_rates)*100:.1f}%  "
          f"中位 {statistics.median(warm_rates)*100:.1f}%  "
          f"P(失败率>30%) = {sum(r > 0.3 for r in warm_rates)/ROUNDS*100:.1f}%")

    # 场景 B：冷缓存（进程刚启动就点测速，系统回调 50~300ms 后才来）
    cold_rates = []
    cold_allfail = 0
    for _ in range(ROUNDS):
        failed = simulate(warm_latency_ms=random.uniform(50, 300))
        rate = sum(failed) / N_TESTS
        cold_rates.append(rate)
        if rate >= 0.8:
            cold_allfail += 1
    print(f"[冷缓存] 平均失败率 {statistics.mean(cold_rates)*100:.1f}%  "
          f"P(几乎全炸>=80%) = {cold_allfail/ROUNDS*100:.1f}%")

    # 场景 C：对照组——若 wrapper 为每 box 独立（修复方案），回调恒刷自己的缓存
    # 此时唯一失败来源是冷缓存期间 defaultInterface 尚未设置（旧版异步注册竞态），
    # 同步注册 + 缓存命中下不存在失败。
    print("[对照] 每 box 独立 wrapper：UpdateInterfaces 恒刷自己的 NetworkManager，"
          "本竞态失败率 = 0%")


if __name__ == "__main__":
    main()
