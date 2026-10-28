/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A document the user picked through the system file picker (Storage Access Framework). */
data class PickedDocument(val displayName: String, val bytes: ByteArray)

/** Reads and writes documents by content URI, so view models never touch a Context. */
interface DocumentIo {
    /** Throws [IOException] with a readable message when the document can't be read or is too large. */
    suspend fun read(uri: Uri): PickedDocument
    suspend fun write(uri: Uri, bytes: ByteArray)
}

@Singleton
class ContentResolverDocumentIo @Inject constructor(@ApplicationContext private val context: Context) : DocumentIo {
    override suspend fun read(uri: Uri): PickedDocument = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Imported"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("The file is larger than ${MAX_BYTES / 1_000_000} MB")
            }
            out.toByteArray()
        } ?: throw IOException("The file could not be opened")
        PickedDocument(name, bytes)
    }

    override suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        // "wt": truncate, so overwriting a longer file leaves no stale tail.
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("The file could not be written")
    }

    companion object {
        /** DWSIM files of typical flowsheets are well under 1 MB; this guards memory and the upload. */
        const val MAX_BYTES = 20_000_000
    }
}
