# Phase2 收尾验证：PURCHASE 落库 + 完整双删（成功更新场景）
import subprocess, time, re
import requests, pymysql

BASE = "http://localhost:8080"

def redis_get(key):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "GET", key], capture_output=True, text=True)
    return r.stdout.strip()

def redis_keys(pattern):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "KEYS", pattern], capture_output=True, text=True)
    return [l for l in r.stdout.splitlines() if l.strip()]

def ok(j):
    return j.get("code") in (0, 200)

def login(account, password):
    cid = requests.get(BASE + "/api/auth/captcha").json()["data"]["captchaId"]
    code = redis_get(f"captcha:{cid}")
    j = requests.post(BASE + "/api/auth/login", json={"account": account, "password": password, "captchaId": cid, "captchaCode": code}).json()
    assert ok(j), f"login fail {j}"
    return j["data"]

def main():
    print("== 核对 PURCHASE 行为落库（链路② 消费者已执行） ==", flush=True)
    conn = pymysql.connect(host="localhost", port=3306, user="root", password="719892144Qq", database="mall_db", charset="utf8mb4")
    cur = conn.cursor()
    cur.execute("SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=4 GROUP BY behavior_type")
    print(f"  user_behaviors(user4): {cur.fetchall()}", flush=True)
    cur.execute("SELECT product_id, behavior_type FROM user_behaviors WHERE user_id=4 AND behavior_type='PURCHASE' ORDER BY id DESC LIMIT 3")
    print(f"  PURCHASE 明细: {cur.fetchall()}", flush=True)

    print("== 完整双删：admin 成功更新商品1 -> 重建缓存 -> 1s后延迟再删 ==", flush=True)
    adata = login("admin", "123456")
    AH = {"Authorization": f"Bearer {adata.get('token') or adata.get('accessToken')}"}
    # 先请求详情/列表重建缓存
    requests.get(BASE + "/api/products/1")
    requests.get(BASE + "/api/products?page=1&pageSize=5")
    before = redis_keys("*product*")
    print(f"  更新前缓存: {before}", flush=True)
    upd = {"name": "法式碎花连衣裙", "price": 199.0, "categoryId": 1, "description": "双删成功场景", "status": 1,
           "sku": "P1-TEST", "stock": 47}
    r = requests.put(BASE + "/api/admin/products/1", json=upd, headers=AH)
    print(f"  update resp={r.status_code} body={r.text[:120]}", flush=True)
    time.sleep(3)
    after = redis_keys("*product*")
    print(f"  更新后3s缓存: {after}", flush=True)
    cache_left = [k for k in after if "cache:product" in k]
    print(f"  双删结论: cache 残留={cache_left} （期望 []：先删+延迟再删都执行）", flush=True)
    conn.close()

if __name__ == "__main__":
    main()
