"""符号级校验：libcore 用到的关键 sing-box 官方符号必须存在于官方源码树。

用法（在仓库根目录执行）：uv run tools/diagnostics/roo_check_symbols.py [sing-box源码目录]
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SING_BOX = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO.parent / "sing-box"

# (相对路径 glob, 必须匹配的正则, 说明)
CHECKS = [
    ("constant", r"RuleSetVersionCurrent", "srs.Write 版本常量"),
    ("constant", r'RuleSetTypeLocal\s*=', "local rule-set 类型常量"),
    ("constant", r'RuleTypeDefault\s*=', "默认规则类型常量"),
    ("constant", r"DNSTypeLocal", "local DNS 类型常量"),
    ("constant", r"DefaultDNSTTL", "DNS TTL 常量"),
    ("experimental/v2rayapi", r"func NewStatsService\(options option\.V2RayStatsServiceOptions\)", "统计服务构造"),
    ("experimental/v2rayapi", r"Reset_", "GetStatsRequest.Reset_ 字段"),
    ("option", r"V2RayStatsServiceOptions", "统计选项结构"),
    ("common/srs", r"func Write\(writer io\.Writer, ruleSet option\.PlainRuleSet, generateVersion uint8\)", "srs.Write 签名"),
    ("common/geosite", r"func Compile\(code \[\]Item\) option\.DefaultRule", "geosite.Compile 签名"),
    ("common/dialer", r"func New\(ctx context\.Context, options option\.DialerOptions, remoteIsDomain bool\)", "dialer.New 签名"),
    ("common/tls", r"func NewDialerFromOptions\(ctx context\.Context, logger logger\.ContextLogger", "tls.NewDialerFromOptions 签名"),
    ("common/tls", r"func NewSTDClient\(ctx context\.Context, logger logger\.ContextLogger", "tls.NewSTDClient 签名"),
    ("adapter", r"type PlatformInterface interface", "平台接口"),
    ("adapter", r"FindConnectionOwner\(request \*FindConnectionOwnerRequest\)", "连接属主查询"),
    ("adapter", r"OpenInterface\(options \*tun\.Options, platformOptions option\.TunPlatformOptions\)", "TUN 打开"),
    ("dns", r"func NewTransportAdapterWithLocalOptions", "DNS TransportAdapter"),
    ("dns", r"func FixedResponse\(id uint16", "dns.FixedResponse"),
    ("dns", r"func NewTransportRegistry\(\)", "DNS registry 构造"),
    ("log", r"type PlatformWriter interface", "日志 PlatformWriter"),
    ("log", r"WriteMessage\(level Level, message string\)", "WriteMessage 签名"),
    ("protocol/group", r"func \(s \*Selector\) SelectOutbound\(tag string\) bool", "selector 切换"),
    ("option", r"TunPlatformOptions", "TUN 平台选项"),
    ("option", r"LocalDNSServerOptions", "local DNS 选项"),
    ("route", r"func \(r \*Router\) AppendTracker\(tracker adapter\.ConnectionTracker\)", "统计 tracker 挂载"),
    ("dns/transport/fakeip", r"func RegisterTransport", "fakeip 注册"),
    ("dns/transport/hosts", r"func RegisterTransport", "hosts 注册"),
    ("dns/transport/local", r"func RegisterTransport", "local 注册"),
    ("dns/transport/quic", r"func RegisterHTTP3Transport", "h3 DNS 注册"),
]

errors: list[str] = []
for sub, pattern, desc in CHECKS:
    base = SING_BOX / sub
    if not base.exists():
        errors.append(f"{sub}: 目录不存在 ({desc})")
        continue
    text = "\n".join(f.read_text(encoding="utf-8", errors="replace") for f in base.rglob("*.go") if not f.name.endswith("_test.go"))
    if not re.search(pattern, text):
        errors.append(f"{sub}: 未找到 `{pattern}` ({desc})")

print(f"checked {len(CHECKS)} symbols against {SING_BOX}")
if errors:
    print(f"\n{len(errors)} error(s):")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("OK: all symbols found in official tree")
