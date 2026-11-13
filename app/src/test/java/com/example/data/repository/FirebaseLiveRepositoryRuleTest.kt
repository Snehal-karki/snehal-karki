package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class FirebaseLiveRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun upsertUserProfile_authenticatedOwner_succeedsAndReadsBack() = runBlocking {
        signInTestUser("parent_${UUID.randomUUID()}@test.com")
        val repo = FirebaseLiveRepository(firestore, auth)

        val res = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Snehal Parent",
                role = "parent",
                familyId = "fam-123",
                supabaseUserId = "supa-parent-1"
            )
        }
        assertTrue("Upsert user profile should succeed", res.isSuccess)

        val profileRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile() }
        assertTrue(profileRes.isSuccess)
        assertEquals("Snehal Parent", profileRes.getOrNull()?.displayName)
        assertEquals("parent", profileRes.getOrNull()?.role)
    }

    @Test
    fun screenStream_crossFamilyPairedParent_observesStreamAndStrangerDenied() = runBlocking {
        val parentEmail = "parent_pair_${UUID.randomUUID().toString().take(8)}@test.com"
        val childEmail = "child_pair_${UUID.randomUUID().toString().take(8)}@test.com"
        val strangerEmail = "stranger_${UUID.randomUUID().toString().take(8)}@test.com"

        val parentUid = signInTestUser(parentEmail)
        auth.signOut()
        val childUid = signInTestUser(childEmail)
        val childId = "child_${UUID.randomUUID().toString().take(8)}"
        val childRepo = FirebaseLiveRepository(firestore, auth)

        val pushRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            childRepo.pushScreenFrame(
                childId = childId,
                childName = "Child One",
                foregroundApp = "Khan Academy",
                frameBase64 = "aGVsbG9mcmFtZQ==",
                width = 360,
                height = 720,
                isStreaming = true,
                fps = 1.0,
                memberUids = listOf(childUid, parentUid)
            )
        }
        assertTrue("Child screen push must succeed", pushRes.isSuccess)

        // Parent observes Child screen stream in real time
        auth.signOut()
        signInTestUser(parentEmail)
        val parentRepo = FirebaseLiveRepository(firestore, auth)
        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            parentRepo.observeScreenStream(childId).first { it != null }
        }
        assertNotNull(observed)
        assertEquals("Khan Academy", observed?.foregroundApp)
        assertEquals("aGVsbG9mcmFtZQ==", observed?.frameBase64)

        val watchRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            parentRepo.requestOrStopScreenWatch(
                childId = childId,
                childName = "Child One",
                memberUids = listOf(parentUid, childUid),
                watchRequested = true
            )
        }
        assertTrue("Parent request watch must succeed", watchRes.isSuccess)

        // Unpaired stranger cannot observe Child screen stream
        auth.signOut()
        signInTestUser(strangerEmail)
        val strangerRepo = FirebaseLiveRepository(firestore, auth)
        val strangerObserved = withTimeout(FLOW_TIMEOUT_MS) {
            strangerRepo.observeScreenStream(childId).first()
        }
        assertEquals(null, strangerObserved)

        // Stranger trying to hijack document fails with PERMISSION_DENIED
        val hijackRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            strangerRepo.requestOrStopScreenWatch(
                childId = childId,
                childName = "Hijack",
                memberUids = emptyList(),
                watchRequested = true
            )
        }
        assertTrue("Stranger hijack attempt must fail", hijackRes.isFailure)
        val firestoreErr = hijackRes.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreErr?.code)
    }

    @Test
    fun appUsageStats_authenticatedSyncAndObserve_succeedsAndUnauthenticatedFails() = runBlocking {
        val childEmail = "usage_child_${UUID.randomUUID().toString().take(8)}@test.com"
        val childUid = signInTestUser(childEmail)
        val subjectId = "subj_${UUID.randomUUID().toString().take(8)}"
        val repo = FirebaseLiveRepository(firestore, auth)

        val syncRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncUsageStatsBatch(
                subjectUserId = subjectId,
                role = "child",
                dateKey = "2026-11-13",
                memberUids = listOf(childUid),
                entries = listOf(
                    AppUsageEntry(
                        packageName = "com.google.android.youtube",
                        appName = "YouTube",
                        category = "Entertainment",
                        foregroundTimeMs = 2_400_000L,
                        totalTimeMinutes = 40,
                        launchCount = 5,
                        isBlocked = false
                    )
                )
            )
        }
        assertTrue("Usage stats sync must succeed", syncRes.isSuccess)
        assertEquals(1, syncRes.getOrNull())

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeAppUsageStats(subjectId, "2026-11-13").first { it.isNotEmpty() }
        }
        assertEquals(1, observed.size)
        assertEquals(40L, observed.first().totalTimeMinutes)

        // Unauthenticated caller must fail before/at rule layer
        auth.signOut()
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeAppUsageStats(subjectId, "2026-11-13").first()
            }
            fail("Expected failure when unauthenticated")
        } catch (expected: Exception) {
            // Either IllegalStateException from requireUserId or PERMISSION_DENIED
            assertTrue(expected is IllegalStateException || expected.unwrapFirestoreException() != null)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
