import pymysql
c = pymysql.connect(host='localhost', port=3307, user='root', password='Mall@2026Root', database='mall_db', charset='utf8mb4')
cur = c.cursor()
cur.execute("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='mall_db' AND TABLE_NAME LIKE 'orders%' ORDER BY TABLE_NAME")
for r in cur.fetchall():
    print(r[0])
cur.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='mall_db' AND TABLE_NAME='orders'")
print('orders base exists:', cur.fetchone()[0] == 1)
cur.close()
c.close()
