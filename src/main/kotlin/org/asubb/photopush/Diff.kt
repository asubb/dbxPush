package org.asubb.photopush

import com.dropbox.DropboxFileHash
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import java.io.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

data class DbxFile(
        val name: String,
        val clientModified: LocalDateTime,
        val serverModified: LocalDateTime,
        val path: String,
        val hash: String,
        val size: Long
) : Serializable

data class LocalFile(
        val name: String,
        val path: String,
        val hash: String,
        val size: Long,
        val ref: File
)


class Diff(
        private val localRootPath: String,
        val remoteRootPath: String,
        val dbxClient: DbxClientV2
) {

    private val dbxDataFile = File("dbxFiles.dat")
    private val remoteBase = File(remoteRootPath)
    private val localBase = File(localRootPath)
    private val dbxFiles = readDropboxState(dbxClient, remoteRootPath)
    private var localFiles = readLocalState(localRootPath)

    fun rereadLocal() {
        localFiles = readLocalState(localRootPath)
    }

//    fun get() {
//        checkFileUniqueness(dbxFiles, { File(it.path).toRelativeString(File(remoteRootPath)) })
//        checkFileUniqueness(localFiles, { it.name })

//    }

//    private fun <T> checkFileUniqueness(fileList: List<T>, getFileName: (T) -> String) {
//        // TODO handle that situation properly
//        val groupedByName = fileList.asSequence().groupBy { getFileName(it) }
//        if (groupedByName.count() != fileList.size) {
//            groupedByName.asSequence()
//                    .filter { it.value.size > 1 }
//                    .flatMap { it.value.asSequence() }
//                    .forEach { println(it) }
//            throw UnsupportedOperationException("File names are not unique")
//        }
//    }
//

    fun finalize() {
        dbxDataFile.delete()
    }

    fun getStrict(outputQueue: Queue<File>) {
        var found = 0L
        var notFound = 0L
        val dbxRef = dbxFiles.associate { File(it.path).toRelativeString(remoteBase).toLowerCase() to it }
        localFiles.forEach { localFile ->
            val dbx = dbxRef[localFile.ref.toRelativeString(localBase).toLowerCase()]
            if (dbx == null) {
                // no file found remotely need to upload it
                outputQueue += localFile.ref
                notFound++
            } else {
                if (dbx.size != localFile.size || dbx.hash != localFile.hash) {
                    System.err.println("""
                            WARNING: dbx file clash. Skipping.
                            Remote file: $dbx
                            Local file: ${localFile.name}, ${localFile.size} bytes, hash: `${localFile.hash}`
                        """.trimIndent())
                }
                found++
            }
        }
        println("Already uploaded: $found, to upload: $notFound")
    }

    fun findMisplaced(): Map<File, List<String>> {
        val dbxRef = dbxFiles.groupBy { it.name }
        val misplaced = HashMap<File, List<String>>()
        localFiles.forEach { localFile ->
            val dbx = dbxRef[localFile.name]
            if (dbx != null) {
                val possibleMisplaced = dbx.asSequence()
                        // find identical files
                        .filter { it.size == localFile.size && it.hash == localFile.hash }
                        // which has different paths
                        .filter { File(it.path).toRelativeString(remoteBase).toLowerCase() != localFile.ref.toRelativeString(localBase).toLowerCase() }
                        .map { it.path }
                        .toList()

                if (possibleMisplaced.isNotEmpty()) {
                    misplaced.put(localFile.ref, possibleMisplaced)
                }
            }
        }
        return misplaced
    }


    private fun readLocalState(path: String): List<LocalFile> {
        println("Reading local state...")
        val localFiles = ArrayList<LocalFile>()
        listAllFilesInDirectory(path, localFiles)
        val o = localFiles.filter { it.name !in skipList }
        println("Found ${o.size} files to sync up")
        return o
    }

    private fun readDropboxState(client: DbxClientV2, path: String): List<DbxFile> {
        println("Reading DropBox state...")
        val dbxFiles = ArrayList<DbxFile>()

        if (!dbxDataFile.exists()) {
            listAllDbxFilesInFolder(client, path, dbxFiles)
            ObjectOutputStream(FileOutputStream(dbxDataFile)).use { oos ->
                dbxFiles.forEach {
                    oos.writeObject(it)
                }
            }
        } else {
            println("Used local cache.")
            ObjectInputStream(FileInputStream(dbxDataFile)).use { ois ->
                try {
                    do {
                        val element = ois.readObject() as DbxFile
                        dbxFiles += element
                    } while (true)
                } catch (e: EOFException) {
                    // end of stream, simply finish reading.
                }
            }
        }

        println("Uploaded ${dbxFiles.size} files.")
        return dbxFiles
    }

    private fun listAllFilesInDirectory(path: String, accum: MutableList<LocalFile>) {
        val curdir = File(path)
        if (!curdir.isDirectory) return
        println("Reading $path")
        (curdir.list()?.toList() ?: emptyList<String>()).forEach {
            val f = File("$path/$it")
            if (f.isDirectory)
                listAllFilesInDirectory("$path/$it", accum)
            else
                accum += LocalFile(
                        f.name,
                        f.path,
                        DropboxFileHash(f).asHex()!!,
                        FileInputStream(f).use { it.channel.size() },
                        f
                )
        }
    }

    private fun listAllDbxFilesInFolder(client: DbxClientV2, path: String, accum: MutableList<DbxFile>) {
        println("Reading $path")
        var result = client.files().listFolder(path)

        do {
            for (metadata in result.entries) {
                when (metadata) {
                    is FolderMetadata -> listAllDbxFilesInFolder(client, "$path/${metadata.name}", accum)
                    is FileMetadata -> accum += DbxFile(
                            metadata.name,
                            LocalDateTime.ofInstant(metadata.clientModified.toInstant(), ZoneId.of("UTC")),
                            LocalDateTime.ofInstant(metadata.serverModified.toInstant(), ZoneId.of("UTC")),
                            metadata.pathDisplay,
                            metadata.contentHash,
                            metadata.size

                    )
                    else -> throw UnsupportedOperationException("${metadata.javaClass} is not supported")
                }
            }

            if (result.hasMore)
                result = client.files().listFolderContinue(result.cursor)
        } while (result.hasMore)
    }
}