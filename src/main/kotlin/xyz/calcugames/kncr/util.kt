package xyz.calcugames.kncr

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File

val logger = KotlinLogging.logger("KNCR")

val os = System.getProperty("os.name").substringBefore(" ").lowercase()
val arch = when(System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64", "x64" -> "x64"
    "aarch64", "arm", "arm64" -> "arm64"
    "x86" -> "x86"
    else -> error("Unsupported architecture")
}

val globalCompilerOpts = mapOf(
    "windows" to "-DWIN32 -D_WINDOWS",
    "macos" to "-lpthread -ldl -lm -lrt -I/usr/local/include",
    "linux" to "-lpthread -ldl -lm -lrt -I/usr/include/x86_64-linux-gnu",
)

val globalLinkerOpts = mapOf(
    "windows" to "-DWIN32 -D_WINDOWS",
    "macos" to "-lpthread -ldl -lm -lrt",
    "linux" to "-lpthread -ldl -lm -lrt",
)

val buildSystemCommands: MutableMap<String, List<Command>> = mutableMapOf()

@OptIn(ExperimentalSerializationApi::class)
fun loadBuildSystem() {
    logger.info { "Loading Build Systems..." }
    val buildsStream = logger::class.java.getResourceAsStream("/builds.json") ?: error("builds.json not found")
    val builds = json.decodeFromStream<Map<String, List<Command>>>(buildsStream)

    logger.debug { "Build Types: ${builds.size}" }

    buildSystemCommands.clear()
    buildSystemCommands.putAll(builds)

    logger.info { "Finished Loading Build Systems" }
}

suspend fun String.runCommand(folder: File, pipe: Boolean = false): String? = coroutineScope {
    val str = this@runCommand
    var exitCode = -1

    try {
        val parts = split("\\s".toRegex())
        val builder = ProcessBuilder(*parts.toTypedArray())
            .directory(folder)

        if (pipe) builder.inheritIO()

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
            return@coroutineScope stdout
        else
            return@coroutineScope stdout + "\n" + stderr
    } catch (e: Exception) {
        logger.error(e) { "Failed to run command: '$str' in ${folder.absolutePath}; exit code $exitCode" }
        throw IllegalStateException("Failed to run command: '$str' in ${folder.absolutePath}; exit code $exitCode", e)
    }
}