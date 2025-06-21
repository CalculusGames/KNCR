package xyz.calcugames.kncr

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import kotlin.system.exitProcess

val logger = KotlinLogging.logger("KNCR")

val os = System.getProperty("os.name").substringBefore(" ").lowercase()
val publishOs = when (os) {
    "windows" -> "mingw"
    "mac" -> "macos"
    else -> os
}

val arch = when(System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64", "x64" -> "x64"
    "aarch64", "arm", "arm64" -> "arm64"
    "x86" -> "x86"
    else -> error("Unsupported architecture")
}

val globalCompilerOpts = mapOf(
    "windows" to "-DWIN32 -D_WINDOWS -D__MINGW32__ -D__MINGW64__",
    "macos" to "-I/usr/local/include -I/usr/include -macosx -I/opt/homebrew/include",
    "linux" to "-I/usr/include -I/usr/local/include -I/usr/include/x86_64-linux-gnu",
)

val globalLinkerOpts = mapOf(
    "windows" to "-DWIN32 -D_WINDOWS -D__MINGW32__ -D__MINGW64__",
    "macos" to "-lpthread -ldl -lm -lrt",
    "linux" to "-lpthread -ldl -lm -lrt",
)

val buildSystemCommands: MutableMap<String, List<Command>> = mutableMapOf()

@OptIn(ExperimentalSerializationApi::class)
suspend fun loadBuildSystem() = withContext(Dispatchers.IO) {
    logger.info { "Loading Build Systems..." }
    val buildsStream = logger::class.java.getResourceAsStream("/builds.json") ?: error("builds.json not found")
    val builds = json.decodeFromStream<Map<String, List<Command>>>(buildsStream)

    logger.debug { "Build Types: ${builds.size}" }

    buildSystemCommands.clear()
    buildSystemCommands.putAll(builds)

    logger.info { "Finished Loading Build Systems" }
}

suspend fun String.runCommand(folder: File, pipe: Boolean = false): String? = withContext(Dispatchers.Default) {
    val str = this@runCommand
    var exitCode = -1

    try {
        val parts = split("\\s".toRegex())
        val builder = ProcessBuilder(*parts.toTypedArray())
            .directory(folder)

        if (pipe)
            builder.inheritIO()
        else builder
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)

        val process = builder.start()

        val waiting = launch {
            if (!logger.isDebugEnabled()) return@launch

            while (process.isAlive) {
                logger.debug { "Process '$str' is still running in ${folder.absolutePath}" }
                delay(5000)
            }
        }

        process.waitFor()
        waiting.cancel("Process finished")
        logger.debug { "Process '$str' finished in ${folder.absolutePath}" }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        exitCode = process.exitValue()

        if (exitCode != 0) {
            if (stderr.isNotEmpty() && stdout.isNotEmpty())
                error("STDOUT: $stdout\nSTDERR: $stderr")
            else if (stderr.isNotEmpty())
                error(stderr)
            else if (stdout.isNotEmpty())
                error(stdout)
            else
                error("No output; Exit Code $exitCode")
        }

        if (stderr.isEmpty())
            return@withContext stdout
        else
            return@withContext stdout + "\n" + stderr
    } catch (e: Exception) {
        logger.error(e) { "Failed to run command: '$str' in ${folder.absolutePath}; exit code $exitCode" }
        exitProcess(1)
    }
}