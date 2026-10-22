# -*- coding: utf-8 -*-
import pymysql
c = pymysql.connect(host='127.0.0.1', user='root', password='719892144Qq',
                    database='mall_db', charset='utf8mb4')
cur = c.cursor()
cur.execute("SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=4 AND product_id=255 GROUP BY behavior_type")
print('view-255:', cur.fetchall())
cur.execute('SELECT COUNT(*) FROM user_behaviors')
print('total:', cur.fetchone()[0])
c.close()
