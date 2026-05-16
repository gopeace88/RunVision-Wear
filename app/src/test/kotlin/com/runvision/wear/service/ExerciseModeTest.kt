package com.runvision.wear.service

import org.junit.Assert.*
import org.junit.Test

class ExerciseModeTest {

    @Test
    fun `parses exact enum names`() {
        assertEquals(ExerciseMode.CYCLING, parseExerciseMode("CYCLING"))
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode("RUNNING"))
    }

    @Test
    fun `null defaults to RUNNING (safe)`() {
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode(null))
    }

    @Test
    fun `unknown or malformed defaults to RUNNING (safe)`() {
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode("xyz"))
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode(""))
        assertEquals(ExerciseMode.RUNNING, parseExerciseMode("cycling")) // case-sensitive by design
    }
}
