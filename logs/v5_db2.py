# -*- coding: utf-8 -*-
"""Phase 2 验证辅助 v2：查询 user6 地址"""
import pymysql
conn = pymysql.connect(host='127.0.0.1', user='root', password='719892144Qq',
                       database='mall_db', charset='utf8mb4')
cur = conn.cursor()
cur.execute("SHOW COLUMNS FROM addresses")
print('addresses cols:', [r[0] for r in cur.fetchall()])
cur.execute("SELECT * FROM addresses WHERE user_id=4 LIMIT 5")
for r in cur.fetchall():
    print(r)
conn.close()
