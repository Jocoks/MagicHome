#!/usr/bin/env python3
"""只读查看 / 受控修改 LSPosed 模块数据库。"""
import sqlite3
import sys

DB = "/data/adb/lspd/config/modules_config.db"


def dump_schema(dbc):
    for name, sql in dbc.execute(
            "select name, sql from sqlite_master where type='table'"):
        print("TABLE", name)
        print("  ", sql)
        try:
            cols = [d[0] for d in dbc.execute("select * from %s limit 0" % name).description]
            print("   cols:", cols)
            for row in dbc.execute("select * from %s" % name):
                print("   row:", row)
        except Exception as e:
            print("   err:", e)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "dump"
    db = sqlite3.connect(DB, timeout=10)
    if mode == "dump":
        dump_schema(db)
    elif mode == "enable":
        mid = sys.argv[2]
        scope = sys.argv[3:]
        db.execute("update modules set enabled=1 where mid=?", (mid,))
        for s in scope:
            db.execute(
                "insert or replace into scope(mid, app_pkg_name, user_id) values(?,?,0)",
                (mid, s))
        db.commit()
        print("enabled", mid, "scope", scope)
        dump_schema(db)
    return 0


if __name__ == "__main__":
    sys.exit(main())
