/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A project row. [flowsheetJson] is the master copy of the flowsheet (decision D5). */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "flowsheet_json") val flowsheetJson: String,
    /** Denormalised from [flowsheetJson] for the projects list. */
    @ColumnInfo(name = "object_count") val objectCount: Int = 0,
    /** SHA-256 of [flowsheetJson] at the last successful solve; null if never solved. */
    @ColumnInfo(name = "solved_hash") val solvedHash: String? = null,
)

/** Lightweight row for the projects list. */
data class ProjectSummaryRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "object_count") val objectCount: Int,
    @ColumnInfo(name = "solved_hash") val solvedHash: String?,
)
