package xyz.calcugames.kncr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
}

val s: String = File.separator ?: "/"

val cinteropSuffix = if (os == "windows") ".bat" else ""
val mvnSuffix = if (os == "windows") ".cmd" else ""

private fun countChildren(count: AtomicInteger, job: Job) {
    count.incrementAndGet()
    job.children.forEach { countChildren(count, it) }
}

@OptIn(ExperimentalSerializationApi::class)
suspend fun main(args: Array<String>) = withContext(
    args[4].run {
        if (this == "0") Dispatchers.IO else Dispatchers.IO.limitedParallelism(this.toInt())
    }
) {
    logger.debug { "Arguments: ${args.joinToString()}}" }
    logger.info { "Starting KNCR for $os-$arch"}

    val buildDir = File(args[0])
    logger.debug { "Build Directory: $buildDir" }

    val mavenRemote = args[1]
    logger.debug { "Maven Remote URL: $mavenRemote" }

    val mavenRepository = args[2]
    logger.debug { "Maven Repository ID: $mavenRepository" }

    val task = args[3]
    logger.info { "Runnign Task '$task'" }

    if (args[4] != "0")
        logger.info { "Running with ${args[4]} limited coroutines" }

    if (!buildDir.exists())
        buildDir.mkdirs()

    val cinterop = if (System.getenv("KOTLIN_NATIVE_HOME") != null) {
        "${System.getenv("KOTLIN_NATIVE_HOME")}${s}bin${s}cinterop$cinteropSuffix"
    } else "cinterop$cinteropSuffix"
    logger.debug { "Using CInterop Command: $cinterop" }
    "$cinterop -help".runCommand(buildDir, true)

    val mvn = if (System.getenv("MAVEN_HOME") != null) {
        "${System.getenv("MAVEN_HOME")}${s}bin${s}mvn$mvnSuffix"
    } else "mvn$mvnSuffix"
    logger.debug { "Using Maven Command: $mvn" }

    if (task != "build")
        "$mvn --version".runCommand(buildDir, true)

    loadBuildSystem()
    val repositoriesStream = logger::class.java.getResourceAsStream("/repositories.json") ?: error("Failed to load repositories.json")
    val repositories = json.decodeFromStream<List<Repository>>(repositoriesStream)
    logger.debug { "Repositories Size: ${repositories.size}" }

    logger.info { "Starting KNCR Work" }

    val job = launch {
        for (repo in repositories)
            launch {
                logger.info { "Starting work on '${repo.handle}'" }
                val repoDir = File(buildDir, repo.folder)

                if (repoDir.exists()) {
                    logger.warn { "Repository directory already exists: $repoDir" }
                } else {
                    logger.debug { "Creating repository directory: $repoDir" }
                    repo.clone(buildDir)
                }

                if (File(repoDir, "${repo.name}.klib").exists())
                    logger.info { "Klib file already exists: ${repo.name}.klib" }
                else {
                    if (repo.type == "custom") {
                        val commands =
                            repo.extra["build-cmds"] ?: error("No build commands provided for custom build system")
                        logger.info { "Using custom build system for ${repo.handle}" }

                        for (cmd in commands.split("\n")) {
                            val full = cmd.trim()
                            logger.debug { "Running custom command: $full" }
                            full.runCommand(repoDir)
                        }
                    } else {
                        val commands =
                            buildSystemCommands[repo.type] ?: error("Unknown build system type: ${repo.type}")
                        logger.debug { "Using type ${repo.type} for ${repo.handle}" }

                        for (cmd in commands) {
                            var full = "${cmd.cmd} ${repo.extra[cmd.extra] ?: ""}".trim()
                            if (os == "windows" && repo.type == "cmake" && cmd.extra == "config-flags")
                                full += " -G \"MinGW Makefiles\""

                            full.runCommand(repoDir)
                        }
                    }

                    logger.info { "Generating Definition File for ${repo.handle}..." }
                    val defPath = File(repoDir, "${repo.name}.def")
                    if (defPath.exists()) {
                        logger.warn { "Definition file already exists: $defPath" }
                    } else {
                        val defFile = repo.generateDefinitionFile(repoDir)
                        Files.writeString(defPath.toPath(), defFile)
                        logger.debug { "Generated definition file: $defPath" }
                    }

                    val cinteropCommand = "$cinterop -def ${repo.name}.def -o ${repo.name}.klib"
                    logger.debug { "Running CInterop Command: $cinteropCommand" }

                    cinteropCommand.runCommand(repoDir)
                    defPath.delete()
                    logger.debug { "Deleted definition file: $defPath" }
                }

                if (task == "build") {
                    File(repoDir, "${repo.name}.klib").delete()
                    logger.debug { "Deleted Klib file: ${repo.name}.klib" }

                    logger.info { "Finished work on '${repo.handle}'" }
                    return@launch
                }

                logger.info { "Publishing ${repo.handle}..." }
                val pomPath = File(repoDir, "${repo.name}.pom")
                if (pomPath.exists()) {
                    logger.debug { "POM file already exists: $pomPath" }
                } else {
                    val pomFile = repo.generatePomFile()
                    Files.writeString(pomPath.toPath(), pomFile)
                    logger.debug { "Generated POM file: $pomPath" }
                }

                val publishCommand = when (task) {
                    "install" -> "$mvn install:install-file" +
                            " -Dfile=${repo.name}.klib" +
                            " -DpomFile=${repo.name}.pom" +
                            " -Dpackaging=klib" +
                            " -DcreateChecksum=true"

                    else -> "$mvn deploy:deploy-file" +
                            " -Dfile=${repo.name}.klib" +
                            " -DpomFile=${repo.name}.pom" +
                            " -Durl=$mavenRemote" +
                            " -DrepositoryId=$mavenRepository" +
                            " -Dpackaging=klib"
                }

                logger.debug { "Running Publish Command: $publishCommand" }
                publishCommand.runCommand(repoDir, true)

                pomPath.delete()
                logger.debug { "Deleted POM file: $pomPath" }

                File(repoDir, "${repo.name}.klib").delete()
                logger.debug { "Deleted Klib file: ${repo.name}.klib" }

                logger.info { "Finished work on '${repo.handle}'" }
            }
    }

    if (logger.isDebugEnabled())
        launch(Dispatchers.Default) {
            while (job.isActive) {
                val count = AtomicInteger()
                countChildren(count, job)

                logger.debug { "Parent job running with $count children" }
                delay(5000)
            }
        }
}