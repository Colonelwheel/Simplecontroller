package com.example.simplecontroller.io

import java.util.Locale

data class ControllerProfileBulkDeleteResult(
    val deletedNames: List<String>,
    val failedNames: List<String>,
    val protectedNames: List<String>
)

fun controllerProfileDeleteCandidates(
    savedNames: List<String>,
    activeName: String?
): List<String> = savedNames
    .distinctBy { it.lowercase(Locale.ROOT) }
    .filterNot { it.equals(activeName, ignoreCase = true) }

fun deleteSelectedControllerProfiles(
    selectedNames: List<String>,
    activeName: String?,
    deleteOne: (String) -> Boolean
): ControllerProfileBulkDeleteResult {
    val deleted = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val protected = mutableListOf<String>()

    selectedNames.distinctBy { it.lowercase(Locale.ROOT) }.forEach { name ->
        if (name.equals(activeName, ignoreCase = true)) {
            protected += name
        } else if (deleteOne(name)) {
            deleted += name
        } else {
            failed += name
        }
    }

    return ControllerProfileBulkDeleteResult(
        deletedNames = deleted,
        failedNames = failed,
        protectedNames = protected
    )
}
