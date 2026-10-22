import pymysql
c = pymysql.connect(host="localhost", port=3306, user="root", password="719892144Qq", database="mall_db", charset="utf8mb4")
cur = c.cursor()
cur.execute("SELECT VERSION()")
print("mysql version:", cur.fetchone()[0])
cur.execute("SHOW TABLES")
tables = [r[0] for r in cur.fetchall()]
print("tables:", tables)
for t in tables:
    try:
        cur.execute(f"SELECT COUNT(*) FROM `{t}`")
        n = cur.fetchone()[0]
        if n > 0:
            print(f"  {t}: {n} rows")
    except Exception as e:
        print(f"  {t}: ERR {e}")
# 关键表结构
for t in ("orders", "order_items", "user_behaviors", "inventory_logs", "payments"):
    cur.execute(f"SHOW CREATE TABLE `{t}`")
    ddl = cur.fetchone()[1]
    print(f"\n===== {t} =====")
    print(ddl[:1200])
c.close()
