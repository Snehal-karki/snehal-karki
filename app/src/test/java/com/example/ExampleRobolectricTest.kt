package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ThreatEvaluationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FocusSense", appName)
  }

  @Test
  fun `threat engine detects stranger risk`() {
    val engine = ThreatEvaluationEngine()
    val result = engine.evaluateLayer2MobileBertML(
      appName = "Discord",
      contentTitle = "Direct Message",
      extractedText = "Hey are you alone? Don't tell your mom or dad, let's meet up at the skatepark behind school at 6pm"
    )
    assertTrue("Should be flagged", result.isFlagged)
    assertTrue("Should contain Stranger Risk", result.threatCategory.contains("Stranger Risk"))
    assertTrue("Confidence should be high", result.confidenceScore >= 0.70f)
  }

  @Test
  fun `threat engine marks educational content as safe`() {
    val engine = ThreatEvaluationEngine()
    val result = engine.evaluateLayer2MobileBertML(
      appName = "YouTube",
      contentTitle = "Khan Academy",
      extractedText = "Introduction to cellular biology and photosynthesis in plant cells"
    )
    assertFalse("Should not be flagged", result.isFlagged)
    assertEquals("Safe", result.threatCategory)
  }

  @Test
  fun `schedule rule blocks restricted package during active hours`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = com.example.data.repository.FocusSenseRepository(context)

    // Add an all-day rule restricting Chrome
    val rule = com.example.data.model.ScheduleRuleEntity(
      ruleId = "test-curfew-1",
      childId = "child-default",
      ruleName = "Study Focus",
      category = "Homework",
      startTime = "00:00",
      endTime = "23:59",
      dayOfWeek = "Mon,Tue,Wed,Thu,Fri,Sat,Sun",
      restrictedPackages = "com.android.chrome",
      isActive = true
    )
    repository.addScheduleRule(rule)

    val restriction = repository.checkAppRestriction("child-default", "com.android.chrome")
    assertTrue("com.android.chrome should be blocked during active curfew", restriction.isBlocked)
    assertEquals("Study Focus", restriction.ruleName)

    val allowedRestriction = repository.checkAppRestriction("child-default", "com.example.unrestricted")
    assertFalse("Unrestricted package should not be blocked", allowedRestriction.isBlocked)
  }

  @Test
  fun `instant app lock blocks app immediately`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = com.example.data.repository.FocusSenseRepository(context)

    repository.toggleAppBlock("child-default", "com.google.android.youtube", true)

    assertTrue("Should be blocked by instant lock", repository.isPackageBlockedSync("com.google.android.youtube"))
    val check = repository.checkAppRestriction("child-default", "com.google.android.youtube")
    assertTrue("checkAppRestriction should return blocked", check.isBlocked)

    repository.toggleAppBlock("child-default", "com.google.android.youtube", false)
    assertFalse("Should be unblocked after toggle off", repository.isPackageBlockedSync("com.google.android.youtube"))
  }

  @Test
  fun `channel can be searched allowed and whitelisted for focus study`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = com.example.data.repository.FocusSenseRepository(context)

    val channel = com.example.data.model.AllowedYouTubeChannelEntity(
      channelId = "test-striver-channel",
      childId = "child-default",
      channelName = "Striver",
      handle = "@takeUforward",
      description = "DSA and computer science tutorials",
      category = "Computer Science",
      channelUrl = "https://m.youtube.com/@takeUforward",
      isAllowed = true
    )
    repository.addYouTubeChannel(channel)

    repository.toggleYouTubeChannelAllowed("test-striver-channel", true)
    assertTrue("Channel should be allowed", channel.isAllowed)
    assertEquals("Striver", channel.channelName)
    assertEquals("@takeUforward", channel.handle)
  }

  @Test
  fun `parent role never records location or scans local apps as child telemetry`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.example.data.local.FocusSenseDatabase.getInstance(context)
    val parent = com.example.data.model.UserEntity(
      userId = "parent-test-1",
      groupId = "group-test-1",
      email = "parent@test.com",
      password = "secret",
      role = "parent",
      name = "Parent User"
    )
    val child = com.example.data.model.UserEntity(
      userId = "child-test-1",
      groupId = "group-test-1",
      email = "child@test.com",
      password = "secret",
      role = "child",
      name = "Child User"
    )
    db.userDao().insert(parent)
    db.userDao().insert(child)

    val repository = com.example.data.repository.FocusSenseRepository(context)
    repository.switchUser(parent)
    repository.setSelectedChild("child-test-1")

    // Parent must NOT record location under childId
    repository.recordLocation("child-test-1", 37.77, -122.41, 5f, "Parent GPS")
    val locAfterParent = db.locationDao().getAllLocationsSync().filter { it.childId == "child-test-1" }
    assertTrue("Parent must not record location as child telemetry", locAfterParent.isEmpty())

    // Parent must NOT record screen content under childId
    val ignoredLog = repository.processExtractedContent(
      childId = "child-test-1",
      packageName = "com.example.chat",
      appName = "Chat",
      contentTitle = "Window",
      extractedText = "Hello world",
      forceOffline = true
    )
    assertEquals("", ignoredLog.childId)
    assertEquals("Ignored on parent device", ignoredLog.aiAnalysisSummary)

    // Child CAN record location under its own authenticated userId
    repository.switchUser(child)
    repository.toggleNetworkSimulation(false)
    repository.recordLocation("ignored-arg", 40.7128, -74.0060, 6f, "Child Real Fix")
    val locAfterChild = db.locationDao().getAllLocationsSync().filter { it.childId == "child-test-1" }
    assertEquals(1, locAfterChild.size)
    assertEquals("Child Real Fix", locAfterChild.first().locationName)
  }
}
