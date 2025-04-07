# PyCharm DebugPy Tracer Plugin

This plugin provides a Debug Adapter Protocol (DAP)-based integration between PyCharm and DebugPy, with optional live function tracing and performance profiling support.


---

Download plugin file
[Plugin v1.0.zip](https://github.com/user-attachments/files/19634101/Plugin.v1.0.zip)


---

## 🔧 Debugging Integration

- ✅ **DebugPy as an optional backend** for running Python scripts directly from PyCharm.
- ✅ **DAP Support** with native socket communication instead of custom protocol.
- ✅ **PyCharm ToolWindow UI** with buttons for script selection, execution, and debugging control.

---

## 🧠 Python Tracing & Profiling

- ✅ **Enable function profiling at runtime** by specifying functions via config or command.
- ✅ **Stop tracing anytime** to receive the final execution time summary.
- ✅ **Update trace targets on the fly** without restarting the session (`update:` command).
- ✅ **Show intermediate results** during execution with the `show_stats` command.

---

## ✳️ Key Features

- **Works with any Python script** – no code modification required.
- **Minimal overhead** when tracing is disabled.
- **Readable console output** with formatted statistics and function summaries.
- **Configuration-based tracing** (`config.yaml`) for fine-grained control.
- **Built-in support for DebugPy and DAP commands:**
    - `initialize`
    - `launch`
    - `configurationDone`
    - `setBreakpoints`
    - `continue`
    - `stackTrace`

---

## 📦 Included Buttons (ToolWindow)

- 📂 Select Python File
- ▶ Start DebugPy
- ⏹ Stop Debugging
- 📊 Show Stats
- 🔁 Reload Config
- 🛑 Stop Tracing
- 🧪 Send DAP Commands (initialize, launch, configurationDone)

---

## 📁 Structure

- `run_target.py` – Launch wrapper
- `tracer.py` – Profiling entrypoint
- `config.yaml` – Runtime tracing configuration
- Kotlin frontend – PyCharm plugin (ToolWindow)
- Native socket DAP client (Kotlin)

---

## 🔗 Compatibility
- ✅ Works with PyCharm 2023.1+
- ✅ Requires Python 3.6+
- ✅ Automatically installs `pyyaml` if missing




