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
    /** Model hash of the flowsheet the stored result belongs to; null if never solved. */
    @ColumnInfo(name = "solved_hash") val solvedHash: String? = null,
    /** Last solve result (StoredSolve JSON); null if never solved. */
    @ColumnInfo(name = "result_json") val resultJson: String? = null,
)

/** Lightweight row for the projects list. */
data class ProjectSummaryRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "object_count") val objectCount: Int,
    @ColumnInfo(name = "solved_hash") val solvedHash: String?,
)
