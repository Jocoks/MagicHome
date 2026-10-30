#!/usr/bin/env python3
"""极简 DEX 解析：按方法名找出所属类。用于定位系统 jar 里的目标方法。"""
import re
import struct
import sys
import zipfile


def uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def read_strings(data):
    size, off = struct.unpack_from("<II", data, 0x38)
    out = []
    for i in range(size):
        so = struct.unpack_from("<I", data, off + 4 * i)[0]
        n, p = uleb128(data, so)
        out.append(data[p:p + n].decode("utf-8", "replace"))
    return out


def read_types(data, strings):
    size, off = struct.unpack_from("<II", data, 0x40)
    return [strings[struct.unpack_from("<I", data, off + 4 * i)[0]] for i in range(size)]


def read_methods(data, strings, types):
    size, off = struct.unpack_from("<II", data, 0x58)
    out = []
    for i in range(size):
        cls_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, off + 8 * i)
        out.append((types[cls_idx], strings[name_idx]))
    return out


def scan_jar(path, needle):
    z = zipfile.ZipFile(path)
    dexes = [n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
    for name in dexes:
        data = z.read(name)
        strings = read_strings(data)
        types = read_types(data, strings)
        for cls, mname in read_methods(data, strings, types):
            if needle in mname:
                print("%s!%s -> %s#%s" % (path, name, cls, mname))


if __name__ == "__main__":
    for jar in sys.argv[1:-1]:
        scan_jar(jar, sys.argv[-1])
