#!/usr/bin/env python3
"""启用 com.dsh.novahome 并设置作用域（含备份）。"""
import shutil
import sqlite3
import sys
import time

DB = "/data/adb/lspd/config/modules_config.db"
MID = "com.dsh.novahome"


def main():
    scopes = sys.argv[1:] or [
        "com.hihonor.android.launcher",
        "com.hihonor.hnofficelauncher",
        "android",
    ]
    bak = DB + ".bak-novahome-" + time.strftime("%Y%m%d-%H%M%S")
    shutil.copy2(DB, bak)
    print("backup ->", bak)

    db = sqlite3.connect(DB, timeout=15)
    db.execute("PRAGMA busy_timeout=15000")
    db.execute(
        "insert or replace into modules_state(module_pkg_name,user_id,enabled,scope_request_blocked)"
        " values(?,0,1,0)", (MID,))
    for s in scopes:
        db.execute(
            "insert or replace into scope(module_pkg_name,app_pkg_name,user_id) values(?,?,0)",
            (MID, s))
    db.commit()

    print("enabled:", list(db.execute(
        "select * from modules_state where module_pkg_name=?", (MID,))))
    print("scope:", list(db.execute(
        "select app_pkg_name from scope where module_pkg_name=?", (MID,))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
