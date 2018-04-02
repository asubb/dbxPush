package org.asubb.photopush

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.UploadErrorException
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean


val accessToken = "cIwaPHRHqjYAAAAAAABiT5yq_l_0GeAmJb-u6ZWS0BIaRb-dBssIpBxy2H5BN5BX"

val skipList = setOf(".DS_Store")

fun main(args: Array<String>) {
//    val localRootPath = "/Users/asubb/tmp/iphone-photos"
    val localRootPath = File("/Users/asubb/tmp/iphone-photos").absolutePath!!
    val remoteRootPath = "/photos"
    val restructure = false

    val client = createClient()

    val toUpload = ConcurrentLinkedQueue<File>()
    val pool = Executors.newFixedThreadPool(2)


    val diff = Diff(localRootPath, remoteRootPath, client)

    if (restructure) {
        restructureLocal(diff, remoteRootPath, localRootPath)
    } else {
        val misplaced2 = diff.findMisplaced()
        if (misplaced2.isNotEmpty()) {
            println("Can't sync, too many misplaced:")
            misplaced2.forEach { println("${it.key} -> ${it.value}") }
            throw IllegalStateException("Can't sync, too many misplaced")
        }

        val analyzeComplete = AtomicBoolean(false)
        pool.submit {
            diff.getStrict(toUpload)
            analyzeComplete.set(true)
        }

        val tasks = ArrayList<Future<Boolean>>()
        while (!analyzeComplete.get() || toUpload.size > 0) {

            val fileToUpload: File? = toUpload.poll()
            if (fileToUpload != null) {
                tasks += pool.submit(Callable<Boolean> {
                    uploadFile(fileToUpload, localRootPath, client, remoteRootPath)
                })
            }

            Thread.sleep(1)
        }

        // wait for everything to finish
        val allSucceeded = tasks
                .map(Future<Boolean>::get)
                .all { it }

        pool.shutdown()

        if (allSucceeded || tasks.size == 0)
            diff.finalize()
    }
}

private fun restructureLocal(diff: Diff, remoteRootPath: String, localRootPath: String) {
    val misplaced = diff.findMisplaced()
    if (misplaced.isNotEmpty()) {
        misplaced.asSequence()
                .filter { it.value.size == 1 } // only leave the ones we are confident about
                .forEach {
                    val relativeDir = File(it.value[0]).toRelativeString(File(remoteRootPath))
                    println("Trying to move ${it.key} to $relativeDir")
                    // make sure dir exists
                    val components = relativeDir.split(File.separator)
                    components.subList(0, components.size - 1)
                            .forEachIndexed { index, _ ->
                                val dir = File(localRootPath
                                        + File.separator
                                        + components.subList(0, index + 1).joinToString(File.separator)
                                )
                                if (!dir.exists()) {
                                    println("Creating subdir `$dir`")
                                    dir.mkdirs()
                                }
                            }
                    println("Renaming to ${localRootPath + File.separator + relativeDir}")
                    if (!it.key.renameTo(File(localRootPath + File.separator + relativeDir))) {
                        throw IllegalStateException("Can't move files")
                    }
                }
        diff.rereadLocal()
    }
}

private fun uploadFile(fileToUpload: File, localRootPath: String, client: DbxClientV2, remoteRootPath: String): Boolean {
    val relativePath = fileToUpload.toRelativeString(File(localRootPath))
    val tid = "${Thread.currentThread().id}_${System.nanoTime() % 1024}"
    println("[$tid] Uploading file ${fileToUpload.name} to $relativePath")
    FileInputStream(fileToUpload).use { stream ->
        try {
            // upload file
//            val metadata = client.files()
//                    .uploadBuilder("$remoteRootPath/$relativePath")
//                    .withMode(WriteMode.OVERWRITE)
//                    .uploadAndFinish(stream)
//            println("[$tid] Uploaded `${metadata.name}` ${metadata.size} bytes ${metadata.contentHash}")
//            return true
        } catch (e: UploadErrorException) {
            val stackTrace = e.stackTrace.joinToString("\n")
            System.err.println("[$tid] ${e.message}\n $stackTrace")
        }
        return false
    }
}

private fun createClient(): DbxClientV2 {
    val config = DbxRequestConfig
            .newBuilder("MyPhotoPush")
            .build()
    val client = DbxClientV2(config, accessToken)

    val account = client.users().currentAccount!!
    println("Logged in as ${account.name.displayName}")
    return client
}


