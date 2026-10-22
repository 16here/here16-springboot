import pymysql
c = pymysql.connect(host='localhost', port=3307, user='root', password='Mall@2026Root', database='mall_db', charset='utf8mb4')
cur = c.cursor()
order_id = 106348649994256384

# 1. 直接查 orders_0
cur.execute("SELECT id, order_no, user_id, status FROM orders_0 WHERE id = %s", (order_id,))
r0 = cur.fetchall()
print('orders_0 direct:', r0)

# 2. 广播 SQL（模拟拦截器改写）
sql = """
SELECT * FROM (
  SELECT o.*, u.username FROM orders_0 o LEFT JOIN users u ON o.user_id = u.id
  UNION ALL SELECT o.*, u.username FROM orders_1 o LEFT JOIN users u ON o.user_id = u.id
  UNION ALL SELECT o.*, u.username FROM orders_2 o LEFT JOIN users u ON o.user_id = u.id
  UNION ALL SELECT o.*, u.username FROM orders_3 o LEFT JOIN users u ON o.user_id = u.id
) t WHERE id = %s
"""
try:
    cur.execute(sql, (order_id,))
    print('broadcast rows:', cur.fetchall())
except Exception as e:
    print('broadcast ERROR:', e)

# 3. 看 orders_0 最大 id 与雪花订单是否存在
cur.execute("SELECT MAX(id) FROM orders_0")
print('orders_0 max id:', cur.fetchone()[0])
cur.execute("SELECT COUNT(*) FROM orders_0")
print('orders_0 count:', cur.fetchone()[0])
cur.close()
c.close()
