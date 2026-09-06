"""Inspect Throne desktop .thrbackup and Android JSON backup for import mapping."""
from __future__ import annotations

import json
import sqlite3
import struct
import sys
import tempfile
from pathlib import Path

SQLITE_MAGIC = b"SQLite format 3\x00"
OUT = Path("roo_inspect_backups_out.txt")


class Tee:
    def __init__(self, path: Path):
        self.f = path.open("w", encoding="utf-8")

    def write(self, s: str = ""):
        self.f.write(s + ("\n" if not s.endswith("\n") else ""))

    def close(self):
        self.f.close()


def read_quint32(buf: bytes, off: int):
    return struct.unpack_from("<I", buf, off)[0], off + 4


def read_qstring(buf: bytes, off: int):
    n, off = read_quint32(buf, off)
    if n == 0xFFFFFFFF:
        return None, off
    s = buf[off : off + n].decode("utf-16-le", errors="replace")
    return s, off + n


def read_qbytearray(buf: bytes, off: int):
    n, off = read_quint32(buf, off)
    if n == 0xFFFFFFFF:
        return None, off
    return buf[off : off + n], off + n


def parse_thrbackup(path: Path, log: Tee) -> tuple[dict, dict[str, bytes]]:
    buf = path.read_bytes()
    off = 4
    version, off = read_quint32(buf, off)
    meta_str, off = read_qstring(buf, off)
    meta = json.loads(meta_str)
    count, off = read_quint32(buf, off)
    files: dict[str, bytes] = {}
    for _ in range(count):
        key, off = read_qstring(buf, off)
        val, off = read_qbytearray(buf, off)
        files[key] = val
    log.write(f"=== thrbackup: {path}")
    log.write(f"format_version={version}")
    log.write(json.dumps(meta, indent=2, ensure_ascii=False))
    for k, v in files.items():
        log.write(f"file[{k!r}] size={len(v)} sqlite={v[:16]==SQLITE_MAGIC}")
    return meta, files


def trunc(v, n=600):
    if isinstance(v, bytes):
        try:
            s = v.decode("utf-8")
            return s if len(s) <= n else s[:n] + f"... <len={len(s)}>"
        except Exception:
            return f"<bytes len={len(v)} head={v[:32]!r}>"
    if isinstance(v, str):
        return v if len(v) <= n else v[:n] + f"... <len={len(v)}>"
    return v


def dump_sqlite(db_bytes: bytes, out_dir: Path, log: Tee):
    db_path = out_dir / "throne_backup.db"
    db_path.write_bytes(db_bytes)
    con = sqlite3.connect(str(db_path))
    con.row_factory = sqlite3.Row
    cur = con.cursor()
    tables = [
        r[0]
        for r in cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
    ]
    log.write(f"tables: {tables}")

    for t in tables:
        cols = list(cur.execute(f"PRAGMA table_info({t})"))
        log.write(f"\n## table {t}")
        log.write("columns: " + json.dumps([(c[1], c[2]) for c in cols], ensure_ascii=False))
        n = cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        log.write(f"rows: {n}")

    # entity_ids
    log.write("\n## entity_ids")
    for row in cur.execute("SELECT * FROM entity_ids"):
        log.write(json.dumps(dict(row), ensure_ascii=False))

    # groups
    log.write("\n## ALL groups")
    for row in cur.execute("SELECT * FROM groups"):
        d = {k: trunc(row[k], 400) for k in row.keys()}
        log.write(json.dumps(d, ensure_ascii=False, default=str))

    log.write("\n## groups_order")
    for row in cur.execute("SELECT * FROM groups_order"):
        log.write(json.dumps(dict(row), ensure_ascii=False))

    # profiles
    cols = [c[1] for c in cur.execute("PRAGMA table_info(profiles)")]
    log.write(f"\n## profiles cols: {cols}")
    rows = cur.execute("SELECT type, COUNT(*) c FROM profiles GROUP BY type ORDER BY c DESC").fetchall()
    log.write("type dist: " + json.dumps([dict(r) for r in rows], ensure_ascii=False))

    # one sample per type
    log.write("\n## one sample per type")
    types = [r["type"] for r in rows]
    for t in types:
        row = cur.execute("SELECT * FROM profiles WHERE type=? LIMIT 1", (t,)).fetchone()
        d = {k: trunc(row[k], 1200) for k in row.keys()}
        log.write(f"--- type={t}")
        log.write(json.dumps(d, ensure_ascii=False, default=str))
        # pretty outbound_json if present
        oj = row["outbound_json"]
        if oj:
            try:
                log.write("outbound_json pretty:")
                log.write(json.dumps(json.loads(oj), indent=2, ensure_ascii=False)[:2000])
            except Exception as e:
                log.write(f"outbound_json parse fail: {e}")

    # chain/config special?
    log.write("\n## profiles with non-standard outbound keys sample")
    for row in cur.execute("SELECT id,type,name,outbound_json FROM profiles"):
        try:
            o = json.loads(row["outbound_json"] or "{}")
        except Exception:
            continue
        if o.get("type") not in (
            "shadowsocks",
            "vmess",
            "vless",
            "trojan",
            "hysteria",
            "hysteria2",
            "tuic",
            "socks",
            "http",
            "wireguard",
            "anytls",
            "shadowtls",
            "ssh",
            "naive",
            "direct",
            "block",
            "dns",
            "selector",
            "urltest",
        ):
            log.write(
                json.dumps(
                    {
                        "id": row["id"],
                        "type": row["type"],
                        "name": trunc(row["name"], 80),
                        "outbound_type": o.get("type"),
                        "keys": list(o.keys()),
                        "sample": trunc(json.dumps(o, ensure_ascii=False), 800),
                    },
                    ensure_ascii=False,
                )
            )

    # route_profiles
    log.write("\n## ALL route_profiles")
    for row in cur.execute("SELECT * FROM route_profiles"):
        d = {k: trunc(row[k], 3000) for k in row.keys()}
        log.write(json.dumps(d, ensure_ascii=False, default=str))
        # try parse json fields
        for k in row.keys():
            v = row[k]
            if isinstance(v, str) and v.strip().startswith(("{", "[")):
                try:
                    log.write(f"  parse {k}:")
                    log.write(json.dumps(json.loads(v), indent=2, ensure_ascii=False)[:4000])
                except Exception:
                    pass

    log.write("\n## ALL route_rules")
    for row in cur.execute("SELECT * FROM route_rules"):
        d = {k: trunc(row[k], 1500) for k in row.keys()}
        log.write(json.dumps(d, ensure_ascii=False, default=str))
        for k in row.keys():
            v = row[k]
            if isinstance(v, str) and v.strip().startswith(("{", "[")):
                try:
                    log.write(f"  parse {k}:")
                    log.write(json.dumps(json.loads(v), indent=2, ensure_ascii=False)[:2000])
                except Exception:
                    pass

    log.write("\n## ALL settings")
    # detect schema
    scols = [c[1] for c in cur.execute("PRAGMA table_info(settings)")]
    log.write(f"settings cols: {scols}")
    for row in cur.execute("SELECT * FROM settings ORDER BY 1"):
        d = {k: trunc(row[k], 500) for k in row.keys()}
        log.write(json.dumps(d, ensure_ascii=False, default=str))

    con.close()
    log.write(f"\ndb saved: {db_path}")
    return db_path


def inspect_android_json(path: Path, log: Tee):
    log.write(f"\n=== android json: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    log.write("top keys: " + json.dumps(list(data.keys()), ensure_ascii=False))
    for k, v in data.items():
        if isinstance(v, list):
            log.write(f"{k}: list len={len(v)}")
            if v:
                log.write(f"  first item type={type(v[0]).__name__} prefix={str(v[0])[:100]}")
        else:
            log.write(f"{k}: {v!r}"[:300])


def main():
    log = Tee(OUT)
    thr = Path(
        sys.argv[1]
        if len(sys.argv) > 1
        else r"C:\Users\Tony\Downloads\Throne-backup.thrbackup"
    )
    android = Path(
        sys.argv[2]
        if len(sys.argv) > 2
        else r"C:\Users\Tony\Downloads\throne_backup_2026年8月8日 15_33_26.json"
    )
    out_dir = Path(tempfile.mkdtemp(prefix="thr_inspect_"))
    log.write(f"out_dir: {out_dir}")

    if thr.exists():
        meta, files = parse_thrbackup(thr, log)
        if "database" in files:
            dump_sqlite(files["database"], out_dir, log)
    else:
        log.write(f"thrbackup missing: {thr}")
        cands = list(Path(r"C:\Users\Tony\Downloads").glob("*.thrbackup"))
        log.write(f"candidates: {cands}")
        if cands:
            meta, files = parse_thrbackup(cands[0], log)
            if "database" in files:
                dump_sqlite(files["database"], out_dir, log)

    if android.exists():
        inspect_android_json(android, log)
    else:
        log.write(f"android json missing: {android}")

    log.close()
    print(f"wrote {OUT.resolve()}")


if __name__ == "__main__":
    main()
