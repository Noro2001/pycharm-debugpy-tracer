import debugpy
import sys
import runpy
import socket
import io
import json

# 🔧 Устанавливаем кодировку UTF-8 для stdout (важно на Windows)
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', write_through=True)

def find_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('', 0))
        return s.getsockname()[1]

port = find_free_port()
debugpy.listen(("localhost", port))

# ✅ Выводим в stdout для UI и логов
print(f"✅ DebugPy is listening on port {port}", flush=True)
print(json.dumps({"debugpy_port": port}), flush=True)

# 📍 Ставим breakpoint, но не блокируем исполнение
debugpy.breakpoint()

# ▶ Запуск целевого файла
if len(sys.argv) > 1:
    print(f"▶ Running script: {sys.argv[1]}", flush=True)
    runpy.run_path(sys.argv[1], run_name="__main__")
else:
    print("⚠️ No script provided.", flush=True)
