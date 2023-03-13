package dbx.push

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.fileproperties.PropertyGroup
import com.dropbox.core.v2.files.*
import dev.failsafe.Failsafe
import dev.failsafe.RetryPolicy
import dev.failsafe.function.CheckedRunnable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger


class DropBoxWorker(
    private val client: DbxClientV2,
) {

    lateinit var diff: Diff
    lateinit var localRootPath: String
    lateinit var remoteRootPath: String

    companion object {
        fun create(
            clientIdentifier: String,
            accessToken: String
        ): DropBoxWorker {
            val config = DbxRequestConfig
                .newBuilder(clientIdentifier)
                .build()
            val client = DbxClientV2(config, accessToken)

            val account = client.users().currentAccount!!
            println("Logged in as ${account.name.displayName}")
            return DropBoxWorker(client)
        }
    }

    fun fetchDiff(
        localRootPath: String,
        remoteRootPath: String,
        skipList: Set<String>,
        refresh: Boolean,
    ): DropBoxWorker {
        diff = Diff(localRootPath, remoteRootPath, client, skipList, refresh)
        this.localRootPath = localRootPath
        this.remoteRootPath = remoteRootPath
        return this
    }

    fun uploadFiles(dryRun: Boolean) {
        val misplaced2 = diff.findMisplaced()
        if (misplaced2.isNotEmpty()) {
            println("Can't sync, too many misplaced:")
            misplaced2.forEach {
                println("${it.key} -> ${it.value}")
            }
            throw IllegalStateException("Can't sync, too many misplaced")
        }


        val toUpload = ConcurrentLinkedQueue<File>(diff.getStrict())
        if (!dryRun) {
            runBlocking {
                val counter = AtomicInteger(0);
                counter.addAndGet(toUpload.size)
                val tasks = ArrayList<Deferred<Boolean>>()
                while (toUpload.size > 0) {
                    val fileToUpload: File? = toUpload.poll()
                    if (fileToUpload != null) {
                        val task = async(Dispatchers.IO) {
                            uploadFile(
                                fileToUpload,
                                localRootPath,
                                client,
                                remoteRootPath,
                                counter
                            )
                        }
                        tasks.add(task)
                    }
                }

                // wait for everything to finish
                val allSucceeded = tasks
                    .map { it.await() }
                    .count()

                if (allSucceeded == tasks.size || tasks.size == 0) {
                    println("Upload is successful. Cleaning up local caches")
                    diff.finalize()
                } else {
                    println("Upload had some errors, finished only $allSucceeded out of ${tasks.size}")
                }
            }
        }
    }

    fun restructureLocal() {
        val misplaced = diff.findMisplaced()
        if (misplaced.isNotEmpty()) {
            misplaced.asSequence()
                .filter { it.value.size == 1 } // only leave the ones we are confident about
                .forEach {
                    val relativeDir = File(it.value[0].name).toRelativeString(File(remoteRootPath))
                    val ref =
                        it.key.ref ?: throw IllegalStateException("${it.key} is inappropriate")
                    println("Trying to move ${it.key} to $relativeDir")
                    // make sure dir exists
                    val components = relativeDir.split(File.separator)
                    components.subList(0, components.size - 1)
                        .forEachIndexed { index, _ ->
                            val dir = File(
                                localRootPath
                                        + File.separator
                                        + components.subList(0, index + 1)
                                    .joinToString(File.separator)
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

    private suspend fun uploadFile(
        fileToUpload: File,
        localRootPath: String,
        client: DbxClientV2,
        remoteRootPath: String,
        counter: AtomicInteger
    ): Boolean {
        val relativePath = fileToUpload.toRelativeString(File(localRootPath))
        val tid = "${Thread.currentThread().id}_${System.nanoTime() % 1024}"
        val filePath = "$remoteRootPath/$relativePath"
        val fileAttr = Files.readAttributes(fileToUpload.toPath(), BasicFileAttributes::class.java)
        println("[$tid] Uploading file ${fileToUpload.name} to $filePath")
        FileInputStream(fileToUpload).use { stream ->
            return try {
                val retryPolicy = RetryPolicy.builder<Any>()
                    .handle(com.dropbox.core.NetworkIOException::class.java)
                    .handle(com.dropbox.core.RateLimitException::class.java)
                    .withBackoff(3, 30, ChronoUnit.SECONDS, 3.0)
                    .withJitter(0.1)
                    .withMaxRetries(10)
                    .onRetry {
                        println("[$tid][Retrying] attemptCount=${it.attemptCount}, elapsedTime=${it.elapsedTime}, lastResult=${it.lastResult}")
                    }
                    .build()
                Failsafe.with(retryPolicy)
                    .run(CheckedRunnable {
                        val metadata =
                            doUpload(fileToUpload, client, stream, filePath, fileAttr, tid, counter)
                        println("[$tid, left=${counter.decrementAndGet()}] Uploaded `${metadata.name}` ${metadata.size} bytes ${metadata.contentHash}")
                    })
                true
            } catch (e: UploadErrorException) {
                val stackTrace = e.stackTrace.joinToString("\n")
                System.err.println("[$tid, left=${counter.decrementAndGet()}] ${e.message}\n $stackTrace")
                false
            }
        }
    }

    private fun doUpload(
        fileToUpload: File,
        client: DbxClientV2,
        stream: FileInputStream,
        filePath: String,
        fileAttr: BasicFileAttributes,
        tid: String,
        counter: AtomicInteger
    ): FileMetadata {
        // upload the file
        return if (fileToUpload.length() > 100 * 1024 * 1024) {
            var offset = 0L
            val session = client.files()
                .uploadSessionStart(false)
                .finish()
            val buf = ByteArray(5 * 1024 * 1024)
            do {
                // read
                val amount = stream.read(buf)
                if (amount > 0) {
                    // flush
                    client.files()
                        .uploadSessionAppendV2(
                            UploadSessionCursor(
                                session.sessionId,
                                offset
                            )
                        )
                        .uploadAndFinish(ByteArrayInputStream(buf, 0, amount))
                    offset += amount
                }
            } while (amount != -1)

            // close
            client.files()
                .uploadSessionFinish(
                    UploadSessionCursor(
                        session.sessionId,
                        offset
                    ),
                    CommitInfo(
                        filePath,
                        WriteMode.OVERWRITE,
                        false,
                        Date.from(fileAttr.creationTime().toInstant()),
                        false,
                        emptyList<PropertyGroup>(),
                        false
                    )
                )
                .finish()
        } else {
            client.files()
                .uploadBuilder(filePath)
                .withMode(WriteMode.OVERWRITE)
                .uploadAndFinish(stream)
        }
    }
}