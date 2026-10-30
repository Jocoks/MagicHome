#!/usr/bin/env python3
"""把 aapt2 产出的空 APK 加上 classes.dex 与 META-INF/xposed/ 元数据。"""
import os
import shutil
import sys
import zipfile


def main():
    if len(sys.argv) != 5:
        print("usage: pack.py <base.apk> <out.apk> <classes.dex> <meta_dir>")
        return 2
    base, out, dex, meta_dir = sys.argv[1:5]
    shutil.copyfile(base, out)
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
        for root, _dirs, files in os.walk(meta_dir):
            for name in sorted(files):
                p = os.path.join(root, name)
                arc = os.path.relpath(p, meta_dir)
                z.write(p, arc)
    with zipfile.ZipFile(out) as z:
        names = z.namelist()
    print("packed:", out, "entries:", len(names))
    for n in names:
        print("   ", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
