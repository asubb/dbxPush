package dbx.push

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.UploadErrorException
import com.dropbox.core.v2.files.WriteMode
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.*

class DropBoxWorker(
        private val client: DbxClientV2
) {

    lateinit var diff: Diff
    lateinit var localRootPath: String
    lateinit var remoteRootPath: String

    private val pool = Executors.newFixedThreadPool(1)

    companion object {
        fun create(clientIdentifier: String, accessToken: String): DropBoxWorker {
            val config = DbxRequestConfig
                    .newBuilder(clientIdentifier)
                    .build()
            val client = DbxClientV2(config, accessToken)

            val account = client.users().currentAccount!!
            println("Logged in as ${account.name.displayName}")
            return DropBoxWorker(client)
        }
    }

    fun fetchDiff(localRootPath: String, remoteRootPath: String, skipList: Set<String>): DropBoxWorker {
        diff = Diff(localRootPath, remoteRootPath, client, skipList)
        this.localRootPath = localRootPath
        this.remoteRootPath = remoteRootPath
        return this
    }

    fun uploadFiles() {
        val misplaced2 = diff.findMisplaced()
        if (misplaced2.isNotEmpty()) {
            println("Can't sync, too many misplaced:")
            misplaced2.forEach {
                println("${it.key} -> ${it.value}")
            }
            throw IllegalStateException("Can't sync, too many misplaced")
        }


        val toUpload = ConcurrentLinkedQueue<File>(diff.getStrict())
        val tasks = ArrayList<Future<Boolean>>()
        while (toUpload.size > 0) {

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

    fun restructureLocal() {
        val misplaced = diff.findMisplaced()
        if (misplaced.isNotEmpty()) {
            misplaced.asSequence()
                    .filter { it.value.size == 1 } // only leave the ones we are confident about
                    .forEach {
                        val relativeDir = File(it.value[0].name).toRelativeString(File(remoteRootPath))
                        val ref = it.key.ref ?: throw IllegalStateException("${it.key} is inappropriate")
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
                        if (!ref.renameTo(File(localRootPath + File.separator + relativeDir))) {
                            throw IllegalStateException("Can't move files")
                        }
                    }
            diff.rereadLocal()
        }
    }

    private fun uploadFile(fileToUpload: File, localRootPath: String, client: DbxClientV2, remoteRootPath: String): Boolean {
        val relativePath = fileToUpload.toRelativeString(File(localRootPath))
        val tid = "${Thread.currentThread().id}_${System.nanoTime() % 1024}"
        println("[$tid] Uploading file ${fileToUpload.name} to $remoteRootPath/$relativePath")
        FileInputStream(fileToUpload).use { stream ->
            try {
                // upload file
                if (fileToUpload.length() > 100 * 1024 * 1024) {
                    TODO("dropbox API doesn't support uploading large files without using sessions")
                } else {
                    val metadata = client.files()
                            .uploadBuilder("$remoteRootPath/$relativePath")
                            .withMode(WriteMode.OVERWRITE)
                            .uploadAndFinish(stream)
                    println("[$tid] Uploaded `${metadata.name}` ${metadata.size} bytes ${metadata.contentHash}")
                    return true
                }
            } catch (e: UploadErrorException) {
                val stackTrace = e.stackTrace.joinToString("\n")
                System.err.println("[$tid] ${e.message}\n $stackTrace")
            }
            return false
        }
    }

}