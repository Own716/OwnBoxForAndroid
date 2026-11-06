"""Parse a Throne .thrbackup archive (Qt QDataStream, LittleEndian, Qt_6_0).

Layout (from throneproj/Throne src/ui/setting/dialog_basic_settings.cpp):
  [magic "THRN" 4 bytes]
  [format_version: quint32 LE]
  [metadata: QString]  -- compact JSON
  [files: QMap<QString, QByteArray>]

QDataStream QString  = quint32 byte-length + UTF-16 units (0xFFFFFFFF = null)
QDataStream QByteArray = quint32 length + raw bytes
QMap<K,V> = quint32 count + (K,V) pairs
"""
import json
import struct
import sys

SQLITE_MAGIC = b"SQLite format 3\x00"


def read_quint32(buf, off):
    return struct.unpack_from("<I", buf, off)[0], off + 4


def read_qstring(buf, off):
    n, off = read_quint32(buf, off)
    if n == 0xFFFFFFFF:
        return None, off
    s = buf[off:off + n].decode("utf-16-le", errors="replace")
    return s, off + n


def read_qbytearray(buf, off):
    n, off = read_quint32(buf, off)
    if n == 0xFFFFFFFF:
        return None, off
    return buf[off:off + n], off + n


def main(path):
    buf = open(path, "rb").read()
    print(f"file size: {len(buf)} bytes")
    off = 0

    magic = buf[:4]
    off = 4
    print(f"magic: {magic!r}  (expect b'THRN')")

    version, off = read_quint32(buf, off)
    print(f"format_version: {version}")

    meta_str, off = read_qstring(buf, off)
    print(f"metadata QString ({len(meta_str)} chars):")
    meta = json.loads(meta_str)
    print(json.dumps(meta, indent=2, ensure_ascii=False))

    count, off = read_quint32(buf, off)
    print(f"files map count: {count}")
    for _ in range(count):
        key, off = read_qstring(buf, off)
        val, off = read_qbytearray(buf, off)
        head = val[:16]
        is_sqlite = head == SQLITE_MAGIC
        print(f"  key={key!r}  size={len(val)}  head={head!r}  sqlite={is_sqlite}")

    print(f"consumed {off}/{len(buf)} bytes; trailing={len(buf) - off}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else
         r"C:\Users\Tony\Downloads\Throne-backup.thrbackup")
