/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlinx.serialization.Serializable

/** Title-block details shared by every data sheet of a project. */
@Serializable
data class DocumentInfo(
    val company: String = "",
    val project: String = "",
    val projectNumber: String = "",
    val client: String = "",
    val site: String = "",
    val preparedBy: String = "",
    val checkedBy: String = "",
    val approvedBy: String = "",
)

/** Keys of [SimObject.sheet]: one equipment's own data-sheet fields, all free text. */
object SheetKeys {
    const val DOCUMENT_NUMBER = "documentNumber"
    const val REVISION = "revision"
    const val REVISION_DESCRIPTION = "revisionDescription"
    const val SERVICE = "service"
    const val NOTES = "notes"

    /** Mechanical and design fields, in data-sheet order. Blank ones print as empty boxes to fill in. */
    val DESIGN: List<Pair<String, String>> = listOf(
        "designPressure" to "Design pressure",
        "designTemperature" to "Design temperature",
        "material" to "Material of construction",
        "corrosionAllowance" to "Corrosion allowance",
        "insulation" to "Insulation / tracing",
        "codes" to "Design code / standards",
    )
}
