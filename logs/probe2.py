# -*- coding: utf-8 -*-
import pymysql
c = pymysql.connect(host="localhost", port=3307, user="root", password="Mall@2026Root", database="mall_db", charset="utf8mb4")
cur = c.cursor()
for t in ("user_behaviors", "inventory_logs", "order_items", "order_status_log"):
    cur.execute(f"SHOW CREATE TABLE `{t}`")
    print(f"===== {t} =====")
    print(cur.fetchone()[1])
    print()
c.close()
