import requests, json
BASE = "http://localhost:8080"
for pid in (1, 2, 3):
    j = requests.get(BASE + f"/api/products/{pid}").json()
    d = j.get("data", {})
    skus = d.get("skus") or []
    print(pid, d.get("name"), "price", d.get("price"), "skus", json.dumps(skus[:2], ensure_ascii=False)[:200])
