package com.runvision.wear.service

import androidx.health.services.client.data.ExerciseType

/** Exercise mode chosen by the user on HomeScreen. */
enum class ExerciseMode { RUNNING, CYCLING }

/**
 * Bridge to the Health Services SDK type. Co-located with the enum so the
 * RUNNING↔RUNNING / CYCLING↔BIKING mapping lives in exactly one place.
 */
fun ExerciseMode.toExerciseType(): ExerciseType =
    when (this) {
        ExerciseMode.RUNNING -> ExerciseType.RUNNING
        ExerciseMode.CYCLING -> ExerciseType.BIKING
    }

/**
 * Safe parse for the Intent EXTRA_MODE string. Unknown/null → RUNNING so a
 * malformed or absent command can never accidentally select cycling.
 * Case-sensitive: only exact enum names ("RUNNING"/"CYCLING") match.
 */
fun parseExerciseMode(name: String?): ExerciseMode =
    ExerciseMode.values().firstOrNull { it.name == name } ?: ExerciseMode.RUNNING
