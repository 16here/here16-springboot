# -*- coding: utf-8 -*-
"""Phase 2 验证辅助 v4：将订单 76 创建时间改为 35 分钟前（模拟超时）"""
import pymysql
conn = pymysql.connect(host='127.0.0.1', user='root', password='719892144Qq',
                       database='mall_db', charset='utf8mb4')
cur = conn.cursor()
cur.execute("UPDATE orders SET created_at = DATE_SUB(NOW(), INTERVAL 35 MINUTE) WHERE id = 76")
conn.commit()
cur.execute("SELECT id, order_no, status, created_at FROM orders WHERE id = 76")
print('order76:', cur.fetchall())
conn.close()
