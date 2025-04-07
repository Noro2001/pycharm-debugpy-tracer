package com.example.debugpyplugin

// Import your DAP Client class correctly
// import com.example.debugpyplugin.DebugpyDAPClient

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component // Needed for Alignment constants
import java.awt.Dimension // Needed for RigidArea
import java.io.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.*

class DAPViewerToolWindowFactory : ToolWindowFactory {

    // Coroutine scope tied to the application level or manage per tool window instance if needed
    // Use SupervisorJob so failure of one task doesn't cancel others
    // Using Dispatchers.Default as base, explicitly use SwingUtilities for UI updates
    private val toolWindowScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Keep track of state outside listeners
    @Volatile // Ensure visibility across threads
    private var selectedFile: File? = null
    @Volatile
    private var targetProcess: Process? = null
    @Volatile
    private var tracerProcess: Process? = null
    @Volatile
    private var dapClient: DebugpyDAPClient? = null // Make sure this class exists and is imported

    // --- UI Component Initialization ---
    // Declare UI components here if needed by multiple methods or for complex state mgmt
    // For now, local initialization in createToolWindowContent is fine.

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // --- UI Setup ---
        val mainPanel = JPanel(BorderLayout(5, 5)) // Add gaps between components

        // Control panel with vertical layout for buttons
        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.Y_AXIS) // Vertical stacking
        controlPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5) // Add padding

        // Scroll pane for the buttons, in case there are too many for the window height
        val controlScrollPane = JBScrollPane(
            controlPanel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER // Horizontal scroll unlikely needed for vertical buttons
        )
        controlScrollPane.border = null // Remove default border of scrollpane if desired

        // Output text area
        val output = JTextArea(20, 70).apply {
            isEditable = false
            lineWrap = true // Enable line wrapping
            wrapStyleWord = true // Wrap full words
        }
        val scrollPane = JBScrollPane(output) // Scroll pane for the output

        // Buttons
        val chooseFileButton = JButton("\uD83D\uDCC2 Select Python File")
        val startButton = JButton("\u25B6 Start DebugPy & Tracer")
        val stopButton = JButton("\u23F9 Stop All")
        val showStatsButton = JButton("\uD83D\uDCCA Show Stats")
        val reloadConfigButton = JButton("\uD83D\uDD04 Reload Config")
        val stopTracingButton = JButton("\uD83D\uDEDB Stop Tracer Only")
        val sendDAPButton = JButton("\uD83E\uDDEA Send DAP Launch")

        // --- Helper Functions ---

        fun logOutput(message: String) {
            SwingUtilities.invokeLater { // Ensure UI updates happen on the Event Dispatch Thread (EDT)
                output.append(message + "\n")
                output.caretPosition = output.document.length // Auto-scroll
            }
        }

        fun extractResourceSafely(resourcePath: String, targetFile: File): Boolean {
            targetFile.parentFile?.mkdirs() // Ensure parent directory exists
            return try {
                val input = DAPViewerToolWindowFactory::class.java.getResourceAsStream(resourcePath)
                if (input == null) {
                    logOutput("❌ Resource not found: $resourcePath")
                    println("Error: Resource not found: $resourcePath")
                    return false
                }
                targetFile.outputStream().use { outputStream ->
                    input.use { inputStream -> inputStream.copyTo(outputStream) }
                }
                true
            } catch (e: Exception) {
                logOutput("❌ Failed to extract $resourcePath: ${e.message}")
                e.printStackTrace() // Log stack trace for debugging
                false
            }
        }

        fun findPythonExecutable(): String? { // Returns null on failure
            logOutput("🐍 Searching for Python executable...")
            try {
                // Try 'py -0p' (Windows Python Launcher)
                val process = ProcessBuilder("py", "-0p").start()
                val result = process.inputStream.bufferedReader().use { it.readLines() }
                val exited = process.waitFor(5, TimeUnit.SECONDS)
                if (exited && process.exitValue() == 0) {
                    // Prefer non-starred version
                    result.firstOrNull { it.contains("python.exe", ignoreCase = true) && !it.contains('*') }?.let {
                        val path = it.trim().substringAfterLast(" ").trim()
                        logOutput("ℹ️ Found via 'py -0p': $path")
                        return path
                    }
                    // Fallback to first python.exe entry
                    result.firstOrNull { it.contains("python.exe", ignoreCase = true) }?.let {
                        val path = it.trim().substringAfterLast(" ").trim()
                        logOutput("ℹ️ Found via 'py -0p' (fallback): $path")
                        return path
                    }
                }
            } catch (ioe: IOException) {
                logOutput("ℹ️ 'py -0p' command failed or 'py' not found.")
            } catch (e: Exception) {
                logOutput("⚠️ Error running 'py -0p': ${e.message}")
            }

            // --- Add more detection methods here if needed (PATH, common locations) ---
            // Example: Checking PATH (basic)
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                listOf("python.exe", "python3.exe", "python", "python3").forEach { exe ->
                    val file = File(dir, exe)
                    if (file.exists() && file.canExecute()) {
                        logOutput("ℹ️ Found in PATH: ${file.absolutePath}")
                        return file.absolutePath
                    }
                }
            }


            // If still not found, ask user
            logOutput("⚠️ Could not automatically find Python.")
            var userInput: String? = null
            try {
                // Run JOptionPane on EDT
                SwingUtilities.invokeAndWait {
                    userInput = JOptionPane.showInputDialog(
                        mainPanel, // Parent component for centering dialog
                        "Python executable not found automatically.\nPlease enter the full path:",
                        "Manual Python Path",
                        JOptionPane.QUESTION_MESSAGE
                    )
                }
            } catch (e: Exception) { logOutput("Error showing input dialog: ${e.message}") }


            return if (userInput?.isNotBlank() == true) {
                logOutput("🐍 Using manually entered Python path: $userInput")
                userInput
            } else {
                logOutput("❌ Python path not provided or detection failed.")
                null // Indicate failure
            }
        }

        fun ensurePyYAML(python: String) { // Runs synchronously but should be called from background thread
            logOutput("⚙️ Checking for PyYAML using '$python'...")
            try {
                val checkCommand = listOf(python, "-c", "import sys; import yaml; sys.exit(0)")
                val checkProcess = ProcessBuilder(checkCommand).start()
                val exited = checkProcess.waitFor(10, TimeUnit.SECONDS)

                if (exited && checkProcess.exitValue() == 0) {
                    logOutput("✅ PyYAML is installed.")
                } else {
                    logOutput("📦 PyYAML not found or check failed. Attempting to install...")
                    val installCommand = listOf(python, "-m", "pip", "install", "--user", "pyyaml") // Use --user for safety?
                    val installProcess = ProcessBuilder(installCommand).redirectErrorStream(true).start()

                    // Read output line by line on background thread, update UI via invokeLater
                    installProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> logOutput("  $line") }
                    }

                    val installExited = installProcess.waitFor(60, TimeUnit.SECONDS) // Longer timeout for install
                    if (installExited && installProcess.exitValue() == 0) {
                        logOutput("✅ PyYAML installed successfully.")
                    } else {
                        logOutput("❌ Failed to install PyYAML (Exit code: ${installProcess.exitValue()}). Please install it manually (`$python -m pip install pyyaml`).")
                    }
                }
            } catch (e: Exception) {
                logOutput("❌ Error checking/installing PyYAML: ${e.message}")
                e.printStackTrace()
            }
        }

        fun isProcessRunning(process: Process?): Boolean {
            return process?.isAlive == true
        }

        fun sendToTracer(command: String) {
            val currentTracerProcess = tracerProcess // Capture current value
            if (!isProcessRunning(currentTracerProcess)) {
                logOutput("⚠️ Tracer is not running. Cannot send command: $command")
                return
            }
            try {
                // Use BufferedWriter for potentially better performance and newline handling
                val writer = currentTracerProcess!!.outputStream.bufferedWriter()
                writer.write(command)
                writer.newLine()
                writer.flush()
                logOutput("📤 Sent command to tracer: $command")
            } catch (e: IOException) {
                logOutput("❌ Failed to send command '$command' to tracer: ${e.message}")
                if (!isProcessRunning(currentTracerProcess)) {
                    logOutput("ℹ️ Tracer process appears to have terminated.")
                    tracerProcess = null // Update state if confirmed dead
                }
            } catch (e: Exception) {
                logOutput("❌ Unexpected error sending command '$command' to tracer: ${e.message}")
                e.printStackTrace()
            }
        }

        // Gracefully stops a single process
        fun stopSingleProcess(process: Process?, name: String, forceDelayMs: Long = 500) {
            process?.let { proc ->
                if (proc.isAlive) {
                    logOutput("⏹️ Stopping $name process...")
                    proc.destroy() // SIGTERM
                    val exited = try { proc.waitFor(forceDelayMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
                    if (!exited && proc.isAlive) {
                        logOutput("🔪 Forcibly stopping $name...")
                        proc.destroyForcibly() // SIGKILL
                        try { proc.waitFor(forceDelayMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { /* ignore */ }
                    }
                    logOutput("⏹️ $name process stopped.")
                }
            }
        }

        // Stops all managed processes and clients
        fun stopAllProcessesAndClient() { // Should be called from background thread
            logOutput("⏹️ Stopping all processes and client...")

            // 1. Disconnect DAP Client
            dapClient?.let { client ->
                logOutput("🔌 Disconnecting DAP client...")
                try {
                    client.disconnect()
                } catch (e: Exception) {
                    logOutput("⚠️ Error disconnecting DAP client: ${e.message}")
                }
                dapClient = null
                logOutput("🔌 DAP client disconnected.")
            }

            // 2. Stop Tracer Process (ask nicely first via command if possible)
            tracerProcess?.let { proc ->
                if (proc.isAlive) {
                    logOutput("✉️ Asking tracer to stop...")
                    sendToTracer("stop") // Ask it to shut down
                    try { proc.waitFor(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {} // Give it a moment
                }
                // Now stop it regardless
                stopSingleProcess(proc, "Tracer")
            }
            tracerProcess = null

            // 3. Stop Target Script Process
            stopSingleProcess(targetProcess, "Target Script")
            targetProcess = null

            logOutput("⏹️ Stop sequence complete.")
        }

        // Function to monitor a process's output stream
        fun monitorProcessOutput(process: Process, prefix: String, onExit: (Int?) -> Unit) {
            toolWindowScope.launch(Dispatchers.IO) { // Blocking read operations on IO dispatcher
                var exitCode: Int? = null
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> logOutput("$prefix: $line") }
                    }
                    // If useLines completes, the stream is closed, process likely exited.
                    exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null } // Check exit code if possible
                } catch (e: IOException) {
                    // This often happens when the process is forcefully destroyed
                    logOutput("ℹ️ $prefix output stream closed or error: ${e.message}")
                    exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                } finally {
                    logOutput("🏁 $prefix output monitoring ended.")
                    // Ensure exit code check happens even if stream read fails
                    if (exitCode == null) {
                        try {
                            // Wait briefly if process hasn't registered exit yet
                            process.waitFor(100, TimeUnit.MILLISECONDS)
                            exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                    if (exitCode != null) {
                        logOutput("$prefix process exited with code: $exitCode")
                    }
                    // Call the callback on the EDT
                    SwingUtilities.invokeLater { onExit(exitCode) }
                }
            }
        }

        // --- Action Listeners ---

        chooseFileButton.addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select Python Script to Debug")
                .withDescription("Choose the .py script you want to execute.")
                .withFileFilter { vf -> !vf.isDirectory && "py".equals(vf.extension, ignoreCase = true) }

            val startingDir: VirtualFile? = project.baseDir
            val chosenVFile: VirtualFile? = FileChooser.chooseFile(descriptor, project, startingDir)

            chosenVFile?.let {
                selectedFile = File(it.path)
                logOutput("Selected file: ${it.path}")
            }
        }

        startButton.addActionListener {
            val currentSelectedFile = selectedFile
            if (currentSelectedFile == null) {
                logOutput("⚠️ Please select a .py file first.")
                return@addActionListener
            }

            // Run the whole start sequence in the background
            toolWindowScope.launch(Dispatchers.IO) {
                // 1. Stop existing processes first
                stopAllProcessesAndClient()

                // 2. Prepare Environment
                logOutput("⚙️ Preparing environment...")
                var tempDir: File? = null
                try {
                    tempDir = Files.createTempDirectory("debugpy_plugin_${System.currentTimeMillis()}").toFile()
                    // tempDir.deleteOnExit() // Consider removing if you need to debug script/config files

                    logOutput("🔩 Extracting helper scripts to ${tempDir.absolutePath}...")
                    val dapWrapperScript = File(tempDir, "dap_wrapper.py") // Assumed names
                    val tracerScript = File(tempDir, "tracer.py")
                    val configFile = File(tempDir, "config.yaml")

                    val extractionOk = extractResourceSafely(DAP_WRAPPER_SCRIPT, dapWrapperScript) &&
                            extractResourceSafely(TRACER_SCRIPT, tracerScript) &&
                            extractResourceSafely(CONFIG_FILE, configFile)

                    if (!extractionOk) {
                        logOutput("❌ Critical error: Could not extract necessary script files.")
                        tempDir.deleteRecursively()
                        return@launch
                    }
                    logOutput("✅ Helper scripts extracted.")

                    // 3. Find Python & Check Dependencies
                    val pythonExecutable = findPythonExecutable() // Already logs output
                    if (pythonExecutable == null) {
                        tempDir.deleteRecursively()
                        return@launch
                    }
                    ensurePyYAML(pythonExecutable) // Runs sync, but we are on IO dispatcher

                    // 4. Start Tracer Process
                    logOutput("🚀 Launching tracer...")
                    val tracerCommand = listOf(pythonExecutable, tracerScript.absolutePath)
                    val tracerBuilder = ProcessBuilder(tracerCommand).directory(tempDir).redirectErrorStream(true)
                    tracerProcess = tracerBuilder.start() // Assign to volatile field
                    monitorProcessOutput(tracerProcess!!, "🐍 Tracer") { exitCode ->
                        logOutput("ℹ️ Tracer process exited callback (code: $exitCode)")
                        // Optionally update UI state, e.g., disable tracer buttons
                        tracerProcess = null // Clear reference
                    }
                    logOutput("✅ Tracer process launched (PID: ${tracerProcess?.pid() ?: "N/A"}).")


                    // 5. Start Target Script Process (via wrapper)
                    logOutput("🚀 Launching target script via DAP wrapper...")
                    val targetCommand = listOf(pythonExecutable, dapWrapperScript.absolutePath, currentSelectedFile.absolutePath)
                    val targetBuilder = ProcessBuilder(targetCommand).directory(tempDir).redirectErrorStream(true)
                    targetProcess = targetBuilder.start() // Assign to volatile field
                    monitorProcessOutput(targetProcess!!, "🎯 Target") { exitCode ->
                        logOutput("ℹ️ Target process exited callback (code: $exitCode)")
                        // Optionally update UI, maybe auto-stop tracer?
                        targetProcess = null // Clear reference
                    }
                    logOutput("✅ Target script process launched (PID: ${targetProcess?.pid() ?: "N/A"}).")


                    // 6. Connect DAP Client
                    logOutput("🔌 Attempting to connect DAP client...")
                    delay(1500) // Increase delay slightly for debugpy server to start
                    try {
                        // Ensure DAP Client class exists and is correctly implemented
                        val client = DebugpyDAPClient(port = 5678) // Assuming port
                        client.onOutput { line -> logOutput("🧠 DAP: $line") }
                        client.connect() // This should be a suspending fun or run on IO thread
                        dapClient = client // Assign only after successful connection
                        logOutput("✅ DAP client connected (port 5678).")
                        // Optionally send initial commands now? Or rely on button?
                        // client.sendInitialize() ...
                    } catch (e: Exception) {
                        logOutput("❌ Failed to connect DAP client: ${e.message}")
                        e.printStackTrace()
                        // Don't assign dapClient if connection failed
                        // Consider stopping the other processes if DAP connection is critical
                        // stopAllProcessesAndClient()
                    }

                    logOutput("▶️ Start sequence complete for ${currentSelectedFile.name}.")

                } catch (e: Exception) {
                    logOutput("❌ Unexpected Error during start: ${e.message}")
                    e.printStackTrace()
                    // Ensure cleanup happens even on error
                    stopAllProcessesAndClient() // Call the cleanup function
                    tempDir?.deleteRecursively()
                }
            } // End toolWindowScope.launch
        }


        stopButton.addActionListener {
            toolWindowScope.launch(Dispatchers.IO) { // Run stop sequence in background
                stopAllProcessesAndClient()
            }
        }

        showStatsButton.addActionListener { sendToTracer("show_stats") }

        reloadConfigButton.addActionListener { sendToTracer("update:") } // Adjust command if needed

        stopTracingButton.addActionListener {
            logOutput("⏹️ Stopping tracer process only...")
            toolWindowScope.launch(Dispatchers.IO) {
                // Ask nicely first?
                val currentTracer = tracerProcess
                if(isProcessRunning(currentTracer)){
                    sendToTracer("stop")
                    try { currentTracer?.waitFor(500, TimeUnit.MILLISECONDS) } catch(_:InterruptedException){}
                }
                // Then stop forcefully if needed
                stopSingleProcess(currentTracer, "Tracer")
                if(tracerProcess === currentTracer) { // Avoid race condition if already stopped elsewhere
                    tracerProcess = null
                }
            }
        }

        sendDAPButton.addActionListener {
            val currentDapClient = dapClient // Capture volatile read
            val currentSelectedFile = selectedFile // Capture volatile read

            if (currentDapClient == null) {
                logOutput("⚠️ DAP client is not connected.")
                return@addActionListener
            }
            if (currentSelectedFile == null) {
                logOutput("⚠️ No Python file selected for DAP launch.")
                return@addActionListener
            }

            logOutput("🚀 Sending DAP launch sequence for: ${currentSelectedFile.absolutePath}")
            toolWindowScope.launch(Dispatchers.IO) { // Network ops in background
                try {
                    currentDapClient.sendInitialize()
                    // Assuming launch is correct, adjust if attach is needed
                    currentDapClient.sendLaunch(currentSelectedFile.absolutePath)
                    currentDapClient.sendConfigurationDone()
                    logOutput("✅ Sent DAP initialize, launch, configurationDone.")
                } catch (e: Exception) {
                    logOutput("❌ Error sending DAP commands: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // --- Add Buttons to Control Panel ---
        val buttons = listOf(
            chooseFileButton, startButton, stopButton, showStatsButton,
            reloadConfigButton, stopTracingButton, sendDAPButton
        )

        buttons.forEach { button ->
            // Ensure buttons align left within the BoxLayout
            button.alignmentX = Component.LEFT_ALIGNMENT
            // Set max width to prevent horizontal stretching if desired,
            // but usually LEFT_ALIGNMENT is enough for BoxLayout Y_AXIS.
            // button.maximumSize = Dimension(Short.MAX_VALUE.toInt(), button.preferredSize.height)
            controlPanel.add(button)
            // Add spacing between buttons
            controlPanel.add(Box.createRigidArea(Dimension(0, 8))) // Increased spacing slightly
        }
        controlPanel.add(Box.createVerticalGlue()) // Pushes buttons to the top if extra space


        // --- Assemble Main Panel ---
        mainPanel.add(controlScrollPane, BorderLayout.WEST) // Buttons scroll pane on the left
        mainPanel.add(scrollPane, BorderLayout.CENTER)      // Output text area in the center

        // --- Add Content to Tool Window ---
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // --- Cleanup on Tool Window Dispose ---
        // Consider registering a Disposable to stop processes and cancel scope when the
        // tool window is closed or the plugin is unloaded.
        // Example (requires toolWindow.disposable and implementing Disposable):
        // Disposer.register(toolWindow.disposable, Disposable {
        //     logOutput("Disposing Tool Window Content - Cleaning up...")
        //     toolWindowScope.cancel() // Cancel ongoing coroutines
        //     // Run final stop on a separate thread if needed, as cancel might be abrupt
        //     GlobalScope.launch(Dispatchers.IO){ stopAllProcessesAndClient() }
        // })

    } // End createToolWindowContent

    companion object {
        // Define resource paths as constants
        const val DAP_WRAPPER_SCRIPT = "/python/dap_wrapper.py"
        const val TRACER_SCRIPT = "/python/tracer.py"
        const val CONFIG_FILE = "/python/config.yaml"
    }
}
