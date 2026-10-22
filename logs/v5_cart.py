# -*- coding: utf-8 -*-
import pymysql
c = pymysql.connect(host='127.0.0.1', user='root', password='719892144Qq',
                    database='mall_db', charset='utf8mb4')
cur = c.cursor()
cur.execute('SELECT id, product_id, sku_id, quantity FROM carts WHERE user_id=4 ORDER BY id DESC LIMIT 2')
print('carts:', cur.fetchall())
c.close()
