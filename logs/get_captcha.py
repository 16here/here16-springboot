import subprocess
docker = r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
out = subprocess.run([docker, "exec", "redis", "redis-cli", "GET", "captcha:c13399278005200170"],
                     capture_output=True, text=True, timeout=10)
print("CODE:" + (out.stdout or "").strip())
print("ERR:" + (out.stderr or "")[:200])
