import sys
import time
import threading
import os
import yaml
import io

# ✅ Установка UTF-8 вывода независимо от ОС (important for Windows)
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', write_through=True)

_traced = {}
_start = {}
_functions = set()
_lock = threading.Lock()
_last_mtime = None

def _trace(frame, event, arg):
    if event not in ("call", "return"):
        return _trace
    func = f"{frame.f_code.co_name} ({frame.f_code.co_filename}:{frame.f_lineno})"
    if func not in _functions:
        return _trace
    with _lock:
        if event == "call":
            _start[func] = time.perf_counter()
        elif event == "return" and func in _start:
            duration = time.perf_counter() - _start.pop(func)
            _traced[func] = _traced.get(func, 0) + duration
    return _trace

def load_functions_from_config(path="config.yaml"):
    if not os.path.exists(path):
        print("\u26a0\ufe0f config.yaml not found.")
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
            return data.get("functions_to_trace", [])
    except Exception as e:
        print(f"⚠\ufe0f Failed to load config: {e}")
        return []

def start_tracing(funcs=None):
    if funcs:
        _functions.update(funcs)
    sys.setprofile(_trace)

def stop_tracing():
    sys.setprofile(None)
    print("\u2705 Function Timing Summary:")
    for func, total in _traced.items():
        print(f"🕒 {func}: {total:.6f}s")

def update_traced_functions(funcs):
    _functions.clear()
    _functions.update(funcs)
    print(f"🔁 Updated functions to trace: {list(_functions)}")

def get_stats():
    print("📊 Intermediate Stats:")
    for func, total in _traced.items():
        print(f"🕒 {func}: {total:.6f}s")

# ✅ stdin command listener (for IPC)
def listen_for_commands():
    print("🟢 Tracer ready. Waiting for stdin commands...", flush=True)
    for line in sys.stdin:
        cmd = line.strip()
        if cmd == "stop":
            stop_tracing()
            break
        elif cmd == "show_stats":
            get_stats()
        elif cmd.startswith("update:"):
            new_funcs = cmd.replace("update:", "").split(",")
            update_traced_functions([f.strip() for f in new_funcs if f.strip()])
        else:
            print(f"⚠\ufe0f Unknown command: {cmd}", flush=True)

# 🔁 Watcher for config.yaml changes
def start_config_watcher(path="config.yaml"):
    def watch():
        global _last_mtime
        while True:
            if os.path.exists(path):
                mtime = os.path.getmtime(path)
                if _last_mtime is None:
                    _last_mtime = mtime
                elif mtime != _last_mtime:
                    _last_mtime = mtime
                    print("🔄 config.yaml changed. Reloading...", flush=True)
                    funcs = load_functions_from_config()
                    update_traced_functions(funcs)
            time.sleep(2)  # check every 2 seconds

    thread = threading.Thread(target=watch, daemon=True)
    thread.start()

if __name__ == "__main__":
    funcs = load_functions_from_config()
    start_tracing(funcs)
    start_config_watcher()
    listen_for_commands()