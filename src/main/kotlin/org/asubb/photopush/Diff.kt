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
        private val remoteRootPath: String,
        private val dbxClient: DbxClientV2
) {

    private val dbxDataFile = File("dbxFiles.dat")
    private val remoteBase = File(remoteRootPath)
    private val localBase = File(localRootPath)
    private val dbxFiles = readDropboxState(dbxClient, remoteRootPath)
    private var localFiles = readLocalState(localRootPath)

    fun rereadLocal() {
        localFiles = readLocalState(localRootPath)
    }

    fun finalize() {
        dbxDataFile.delete()
    }

    fun getStrict(): List<File> {
        val outputList = ArrayList<File>()
        var found = 0L
        var notFound = 0L
        val dbxRef = dbxFiles.associate { File(it.path.toLowerCase()).toRelativeString(remoteBase).toLowerCase() to it }
        localFiles.forEach { localFile ->
            val dbx = dbxRef[localFile.ref.toRelativeString(localBase).toLowerCase()]
            if (dbx == null) {
                // no file found remotely need to upload it
                outputList += localFile.ref
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
        return outputList
    }

    data class FileDesc(
            val name: String,
            val size: Long,
            val hash: String,
            val ref: File? = null
    )

    fun findMisplaced(): Map<FileDesc, List<FileDesc>> {
        val dbxRef = dbxFiles.groupBy { it.name }
        val misplaced = HashMap<FileDesc, List<FileDesc>>()
        localFiles.forEach { localFile ->
            val dbx = dbxRef[localFile.name]
            if (dbx != null) {
                val possibleMisplaced = dbx.asSequence()
                        // find identical files
                        .filter { it.size == localFile.size && it.hash == localFile.hash }
                        // which has different paths
                        .filter { !arePathTheSame(it, localFile) }
                        .map {
                            println(">>> `${File(it.path).toRelativeString(remoteBase).toLowerCase()}`")
                            println(">>>> `${localFile.ref.toRelativeString(localBase).toLowerCase()}`")
                            FileDesc(it.path.toLowerCase(), it.size, it.hash)
                        }
                        .toList()

                if (possibleMisplaced.isNotEmpty()) {
                    misplaced[FileDesc(localFile.name, localFile.size, localFile.hash, localFile.ref)] = possibleMisplaced
                }
            }
        }
        return misplaced
    }

    private fun arePathTheSame(remoteFile: DbxFile, localFile: LocalFile): Boolean {
        val remoteFileComponents = remoteFile.path.split(File.separatorChar)
        val remoteBaseComponents = remoteRootPath.split(File.separatorChar)
        val remoteRelativePath = remoteFileComponents.subList(remoteBaseComponents.size, remoteFileComponents.size).joinToString(separator = File.separator)

        val localFileComponents = localFile.path.split(File.separatorChar)
        val localBaseComponents = localRootPath.split(File.separatorChar)
        val localRelativePath = localFileComponents.subList(localBaseComponents.size, localFileComponents.size).joinToString(separator = File.separator)

        return remoteRelativePath.toLowerCase() == localRelativePath.toLowerCase()
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