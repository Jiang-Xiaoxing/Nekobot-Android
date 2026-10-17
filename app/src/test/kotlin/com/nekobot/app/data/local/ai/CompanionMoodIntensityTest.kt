package com.nekobot.app.data.local.ai

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CompanionMoodIntensityTest {
    @Test fun intensityKeepsItsScaleAndBothEndpoints() {
        listOf(0f, 0.41f, 1f).forEach { value ->
            assertEquals(value, requireNotNull(CompanionRelationshipPolicy.parseMoodIntensity(JsonPrimitive(value))), 0.000001f)
        }
    }

    @Test fun unknownIsNotZeroAndMissingFieldIsNotAnAssessment() {
        assertNull(CompanionRelationshipPolicy.parseMoodIntensity(JsonNull.INSTANCE))
        assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipPolicy.parseMoodIntensity(null) }
    }

    @Test fun percentagesOutsideTheContractAndNonFiniteNumbersAreRejected() {
        listOf(-0.1, 1.01, 41.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CompanionRelationshipPolicy.parseMoodIntensity(JsonPrimitive(value))
            }
        }
    }

    @Test fun stringsBooleansAndContainersCannotBecomeIntensity() {
        listOf("\"0.41\"", "\"41%\"", "true", "{}", "[0.41]").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                CompanionRelationshipPolicy.parseMoodIntensity(JsonParser.parseString(json))
            }
        }
    }
}
