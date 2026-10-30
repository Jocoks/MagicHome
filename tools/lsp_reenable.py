#!/usr/bin/env python3
"""把 NovaHome 重新置为启用，并打印状态。"""
import sqlite3

DB = "/data/adb/lspd/config/modules_config.db"
db = sqlite3.connect(DB, timeout=15)
db.execute("PRAGMA busy_timeout=15000")
db.execute("update modules_state set enabled=1 where module_pkg_name='com.dsh.novahome'")
db.commit()
print("rows:")
for r in db.execute(
        "select m.module_pkg_name, s.enabled, s.user_id from modules m "
        "left join modules_state s on s.module_pkg_name=m.module_pkg_name "
        "where m.module_pkg_name='com.dsh.novahome'"):
    print("  ", r)
print("scope:")
for r in db.execute("select app_pkg_name from scope where module_pkg_name='com.dsh.novahome'"):
    print("  ", r[0])
