# -*- coding: utf-8 -*-
import pymysql

for port, tag in [(3307, 'MASTER'), (3308, 'SLAVE')]:
    conn = pymysql.connect(host='127.0.0.1', port=port, user='root', password='Mall@2026Root', database='mall_db', charset='utf8mb4')
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM orders_0 WHERE id IN (85,86)")
    print(tag, 'orders_0 has 85/86:', cur.fetchone()[0], flush=True)
    cur.execute("SELECT COUNT(*) FROM order_items_0 WHERE order_id IN (85,86)")
    print(tag, 'order_items_0 has 85/86:', cur.fetchone()[0], flush=True)
    conn.close()
