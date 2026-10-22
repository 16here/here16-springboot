# -*- coding: utf-8 -*-
import pymysql

def q(port, sql, db=None):
    kw = dict(host="localhost", port=port, user="root", password="Mall@2026Root", charset="utf8mb4")
    if db: kw["database"] = db
    c = pymysql.connect(**kw)
    cur = c.cursor()
    cur.execute(sql)
    rows = cur.fetchall()
    c.close()
    return rows

print("== slave 复制状态 ==", flush=True)
try:
    r = q(3308, "SHOW SLAVE STATUS")
    if not r:
        print("(empty - slave not configured)", flush=True)
    else:
        keys = [d[0] for d in [x for x in []]]
        cols = [d[0] for d in __import__("pymysql").cursors.Cursor().description] if False else None
        desc = None
        # 重新拿列名
        c = pymysql.connect(host="localhost", port=3308, user="root", password="Mall@2026Root", charset="utf8mb4")
        cur = c.cursor(); cur.execute("SHOW SLAVE STATUS"); desc = [d[0] for d in cur.description]; row = cur.fetchone(); c.close()
        for i, k in enumerate(desc):
            if k in ("Slave_IO_Running", "Slave_SQL_Running", "Seconds_Behind_Master", "Last_Error", "Last_IO_Error", "Last_SQL_Error"):
                print(f"  {k}: {row[i]}", flush=True)
except Exception as e:
    print("slave query err:", e, flush=True)

print("== slave 表验证 ==", flush=True)
try:
    r = q(3308, "SELECT COUNT(*) FROM mall_db.orders")
    print("  orders count:", r, flush=True)
    r = q(3308, "SELECT COUNT(*) FROM mall_db.products")
    print("  products count:", r, flush=True)
except Exception as e:
    print("  slave tables err:", e, flush=True)
