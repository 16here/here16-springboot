# -*- coding: utf-8 -*-
"""Phase 2 验证辅助：MySQL 侧查询与数据准备（临时脚本，验证后删除）"""
import pymysql

conn = pymysql.connect(host='127.0.0.1', user='root', password='719892144Qq',
                       database='mall_db', charset='utf8mb4')
cur = conn.cursor()

def show(title, sql, args=None):
    cur.execute(sql, args)
    print(title, cur.fetchall())

show('user6 view product255 behaviors:', 
     "SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=4 AND product_id=255 GROUP BY behavior_type")
show('user_behaviors total:', "SELECT COUNT(*) FROM user_behaviors")
show('user6 cart items:', "SELECT id, product_id, sku_id, quantity FROM carts WHERE user_id=4")
show('user6 addresses:', "SELECT id, receiver_name, phone FROM addresses WHERE user_id=4 LIMIT 3")
show('user6 unpaid orders:', "SELECT id, order_no, status, created_at FROM orders WHERE user_id=4 AND status=0 ORDER BY id DESC LIMIT 3")

conn.close()
print('ok')
