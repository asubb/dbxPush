package dbx.push

import org.apache.commons.cli.*
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.util.*
import kotlin.system.exitProcess

val t = Option("t", "access-token", true, "Token to access DropBox API")
val c = Option("c", "client-identifier", true, "Client Identifier to access DropBox API")
val l = Option("l", "local-path", true, "Path to folder on local machine to make comparison against, i.e. /users/me/Pictures")
val r = Option("r", "remote-path", true, "Path to folder inside DropBox to make comparison against, i.e. /photos")
val s = Option("s", "skip-list", true, "Comma-separate list of files to be skipped")
val a = Option("a", "action", true, "What to do during that run:\n" + Action.values().joinToString("\n") { it.id + " - " + it.description })
val h = Option("h", "help", false, "Prints this help")

val options = Options().of(t, c, l, r, a, s, h)

enum class Action(val id: String, val description: String) {
    RESTRUCTURE("restructure", "Restructure local files that correspond to remote files based on the remote " +
            "folder structure. The one that has no pair will remain untouched."),
    UPLOAD("upload", "Upload current local structure to remote folder. It'll overwrite remote files with new one if it's not the same locally."),
    PREFETCH("prefetch", "Prefetch all files into local cache only"),
    CLEAN_LOCAL("clean-local", "Clean cache: local files structure"),
    CLEAN_DBX("clean-dbx", "Clean cache: dropbox files structure"),
    CONFIG("config", "Store everything to config file to reuse it later"),
}

fun main(args: Array<String>) {
    val propertiesFile = File("dbxpush.properties")

    val properties = Properties()

    if (propertiesFile.exists()) {
        properties.load(FileReader(propertiesFile))
    }

    val cli = try {
        DefaultParser().parse(options, args)
    } catch (e: MissingOptionException) {
        println("ERROR: ${e.message}")
        printHelp()
        exitProcess(1)
    }

    if (cli.has(h)) {
        printHelp()
        exitProcess(0)
    }

    val accessToken = properties.getProperty("access-token") ?: cli.getRequired(t) { it }
    val clientIdentifier = properties.getProperty("client-identifier") ?: cli.getRequired(c) { it }
    val localRootPath = (properties.getProperty("local-path") ?: cli.getRequired(l) { it })
            .let { File(it).absolutePath }
    val remoteRootPath = properties.getProperty("remote-path") ?: cli.getRequired(r) { it }
    val skipList = (properties.getProperty("skip-list") ?: cli.get(s) { it })
            ?.let { it.split(",").map { it.trim() }.toSet() }
            ?: emptySet()
    val action = cli.getRequired(a) { v -> Action.values().first { it.id == v } }

    val client = DropBoxWorker.create(clientIdentifier, accessToken)

    when (action) {
        Action.RESTRUCTURE -> {
        println("Restructuring local files to conform remote")
            client.fetchDiff(localRootPath, remoteRootPath, skipList)
                    .restructureLocal()
        }
        Action.UPLOAD -> {
        println("Starting upload current structure into cloud")
            client.fetchDiff(localRootPath, remoteRootPath, skipList)
                    .uploadFiles()
        }
        Action.PREFETCH -> client.fetchDiff(localRootPath, remoteRootPath, skipList)
        Action.CLEAN_LOCAL -> TODO()
        Action.CLEAN_DBX -> TODO()
        Action.CONFIG -> {
            println("Storing values to config")
            properties.setProperty("access-token", accessToken)
            properties.setProperty("client-identifier", clientIdentifier)
            properties.setProperty("local-path", localRootPath)
            properties.setProperty("remote-path", remoteRootPath)
            properties.setProperty("skip-list", skipList.joinToString(","))
            properties.store(FileWriter(propertiesFile), "DropBox Push configuration file")
        }
    }
}

private fun printHelp() {
    val formatter = HelpFormatter()
    val writer = PrintWriter(System.out)
    formatter.printUsage(writer, 80, "dbxpush", options)
    writer.println()
    formatter.printOptions(writer, 80, options, 0, 0)
    writer.flush()
}



