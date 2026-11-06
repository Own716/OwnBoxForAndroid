"""Verify thrbackup parse + mapping stats for T4A desktop import."""
from __future__ import annotations

import json
import sqlite3
import struct
import sys
import tempfile
from pathlib import Path

OUT = Path("roo_verify_thrbackup_import_out.txt")


def read_u32(buf: bytes, off: int):
    return struct.unpack_from("<I", buf, off)[0], off + 4


def read_qstring(buf: bytes, off: int):
    n, off = read_u32(buf, off)
    if n == 0xFFFFFFFF:
        return None, off
    return buf[off : off + n].decode("utf-16-le", errors="replace"), off + n


def read_qba(buf: bytes, off: int):
    n, off = read_u32(buf, off)
    if n == 0xFFFFFFFF:
        return None, off
    return buf[off : off + n], off + n


def parse_thrbackup(path: Path):
    buf = path.read_bytes()
    off = 4
    ver, off = read_u32(buf, off)
    meta_s, off = read_qstring(buf, off)
    meta = json.loads(meta_s)
    cnt, off = read_u32(buf, off)
    files = {}
    for _ in range(cnt):
        k, off = read_qstring(buf, off)
        v, off = read_qba(buf, off)
        files[k] = v
    return ver, meta, files


def parse_list(raw):
    if not raw:
        return []
    try:
        arr = json.loads(raw)
        return [x for x in arr if str(x).strip()]
    except Exception:
        return []


def map_outbound(oid: int) -> int:
    # T4A: 0 proxy, -1 bypass, -2 block
    if oid == -1:
        return 0
    if oid in (-2, -5):
        return -1
    if oid == -3:
        return -2
    return oid if oid > 0 else 0


SETTINGS_MAP = [
    "remote_dns",
    "direct_dns",
    "enable_dns_routing",
    "fakedns",
    "inbound_socks_port",
    "disable_mixed_inbound",
    "inbound_auth",
    "inbound_user",
    "inbound_pass",
    "inbound_address",
    "log_level",
    "vpn_mtu",
    "vpn_strict_route",
    "vpn_ipv6",
    "vpn_impl",
    "tun_mode_enabled",
    "system_proxy_enabled",
    "test_url",
    "url_test_timeout_ms",
    "test_concurrent",
    "skip_cert",
    "net_insecure",
    "fragment_default_on",
    "fragment_size",
    "fragment_sleep",
    "sniffing_mode",
    "disable_private_range_bypass",
    "core_box_clash_api",
    "outbound_domain_strategy",
    "domain_strategy",
    "remote_dns_strategy",
    "direct_dns_strategy",
    "current_group",
    "remember_id",
    "sub_auto_update",
]


def main():
    path = Path(sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\Tony\Downloads\Throne-backup.thrbackup")
    lines = []
    w = lines.append
    w(f"file={path} exists={path.exists()}")
    ver, meta, files = parse_thrbackup(path)
    w(f"format_version={ver}")
    w(json.dumps(meta, ensure_ascii=False, indent=2))
    w(f"files={list(files)}")
    db_bytes = files["database"]
    db_path = Path(tempfile.gettempdir()) / "t4a_thr_verify.db"
    db_path.write_bytes(db_bytes)
    con = sqlite3.connect(str(db_path))
    con.row_factory = sqlite3.Row

    n_prof = con.execute("select count(*) c from profiles").fetchone()["c"]
    n_grp = con.execute("select count(*) c from groups").fetchone()["c"]
    n_rules = con.execute("select count(*) c from route_rules").fetchone()["c"]
    n_set = con.execute("select count(*) c from settings").fetchone()["c"]
    w(f"counts profiles={n_prof} groups={n_grp} route_rules={n_rules} settings={n_set}")

    types = con.execute("select type, count(*) c from profiles group by type order by c desc").fetchall()
    w("profile types: " + json.dumps([dict(r) for r in types], ensure_ascii=False))

    # groups
    for g in con.execute("select id,name,url,front_proxy_id,landing_proxy_id,profiles_json from groups"):
        ids = parse_list(g["profiles_json"])
        w(
            f"group id={g['id']} name={g['name']!r} sub={bool(g['url'])} "
            f"front={g['front_proxy_id']} land={g['landing_proxy_id']} profiles={len(ids)}"
        )

    # rules mapping
    keep = skip = 0
    mapped = []
    for r in con.execute("select * from route_rules order by rule_order"):
        action = r["action"] or "route"
        if action in ("hijack-dns", "sniff", "resolve"):
            skip += 1
            continue
        domains = []
        for x in parse_list(r["domain_json"]):
            domains.append(x if x.startswith("full:") else f"full:{x}")
        for x in parse_list(r["domain_suffix_json"]):
            domains.append(x if x.startswith("domain:") else f"domain:{x}")
        for x in parse_list(r["domain_keyword_json"]):
            domains.append(x if x.startswith("keyword:") else f"keyword:{x}")
        for x in parse_list(r["domain_regex_json"]):
            domains.append(x if x.startswith("regexp:") else f"regexp:{x}")
        ips = list(parse_list(r["ip_cidr_json"]))
        if r["ip_is_private"]:
            ips.append("geoip:private")
        remote = []
        for rs in parse_list(r["rule_set_json"]):
            if rs.startswith("http://") or rs.startswith("https://"):
                remote.append(rs)
            elif rs.startswith("geoip:") or rs.startswith("geoip-"):
                ips.append(rs)
            elif rs.startswith("geosite:") or rs.startswith("geosite-"):
                domains.append(rs)
            else:
                domains.append(rs)
        proto = r["protocol"] or ""
        if proto.lower() == "dns" and not domains and not ips:
            skip += 1
            continue
        has = bool(domains or ips or remote or proto or (r["network"] or ""))
        if not has:
            skip += 1
            continue
        keep += 1
        mapped.append(
            {
                "name": r["name"],
                "action": action,
                "outbound_desktop": r["outbound_id"],
                "outbound_t4a": map_outbound(r["outbound_id"]) if action != "reject" else -2,
                "domains": domains,
                "ip": ips,
                "ruleset": remote,
                "protocol": proto,
                "network": r["network"],
            }
        )
    w(f"rules keep={keep} skip={skip}")
    for m in mapped:
        w(json.dumps(m, ensure_ascii=False))

    # settings present for mapping
    settings = {row["key"]: row["value"] for row in con.execute("select key,value from settings")}
    present = [k for k in SETTINGS_MAP if k in settings]
    missing = [k for k in SETTINGS_MAP if k not in settings]
    w(f"settings mapped present={len(present)}/{len(SETTINGS_MAP)}")
    for k in present:
        w(f"  {k}={settings[k][:120]}")
    w("settings mapped missing: " + ",".join(missing))

    # outbound_json parse sanity: required fields
    bad = 0
    ok = 0
    for row in con.execute("select id,type,name,outbound_json from profiles"):
        try:
            o = json.loads(row["outbound_json"] or "{}")
        except Exception:
            bad += 1
            continue
        if not o.get("type") or not o.get("server"):
            # may still be custom
            bad += 1
            w(f"profile weak id={row['id']} type={row['type']} keys={list(o.keys())}")
        else:
            ok += 1
    w(f"outbound_json ok_server={ok} weak_or_bad={bad}")

    con.close()
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT.resolve()}")


if __name__ == "__main__":
    main()
