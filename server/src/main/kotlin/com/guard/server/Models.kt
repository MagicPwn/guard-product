package com.guard.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ParentRegisterRequest(val email: String, val password: String)

@Serializable
data class ParentLoginRequest(val email: String, val password: String)

@Serializable
data class AccountDeleteRequest(
    val email: String,
    val password: String,
    val confirmation: String
)

@Serializable
data class ParentView(val id: String, val email: String)

@Serializable
data class AuthResponse(val token: String, val expires_at: Long, val parent: ParentView)

@Serializable
data class ParentSessionView(
    val id: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("last_seen_at") val lastSeenAt: String,
    @kotlinx.serialization.SerialName("revoked_at") val revokedAt: String?,
    val current: Boolean,
    val status: String
)

@Serializable
data class ParentSessionRevokeOthersResponse(val revoked: Int)

@Serializable
data class DeviceRegisterRequest(
    val device_id: String,
    val public_key: String,
    val name: String,
    val platform: String = "android"
)

@Serializable
data class DeviceRegisterResponse(
    val device_token: String,
    val pairing_code: String,
    val pairing_expires_at: Long
)

@Serializable
data class DeviceTokenRequest(
    val device_id: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String
)

@Serializable
data class DeviceTokenResponse(val device_token: String, val expires_at: Long)

@Serializable
data class PairingClaimRequest(val code: String)

@Serializable
data class DeviceView(
    val id: String,
    val device_id: String,
    val name: String,
    val platform: String,
    val paired: Boolean,
    val confirmed: Boolean,
    val revoked: Boolean,
    val created_at: String,
    val last_seen_at: String?,
    val latest_revision: Long
)

@Serializable
data class DeviceMeView(
    val id: String,
    val name: String,
    val paired: Boolean,
    val confirmed: Boolean,
    val parent_email: String?,
    val latest_revision: Long,
    val policy_key_id: String,
    val policy_public_key: String
)

@Serializable
data class PairingConfirmResponse(val paired: Boolean, val confirmed: Boolean)

@Serializable
data class PolicyUpdateRequest(val policy: JsonObject)

@Serializable
data class PolicyEnvelope(
    val schema: Int = 1,
    val key_id: String,
    val revision: Long,
    val payload: String,
    val signature: String
)

@Serializable
data class PolicyHistoryView(
    val revision: Long,
    @kotlinx.serialization.SerialName("key_id") val keyId: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String
)

@Serializable
data class UsageReportRequest(
    val day: String,
    val app_minutes: Map<String, Int>,
    val health: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class UsageReportView(
    val day: String,
    val app_minutes: Map<String, Int>,
    val health: JsonObject,
    val received_at: String
)

@Serializable
data class DashboardAppUsageView(
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    val label: String,
    val minutes: Int,
    val percent: Int
)

@Serializable
data class DashboardDayView(
    val day: String,
    val minutes: Int,
    @kotlinx.serialization.SerialName("quiz_attempts") val quizAttempts: Int,
    @kotlinx.serialization.SerialName("quiz_questions") val quizQuestions: Int,
    @kotlinx.serialization.SerialName("quiz_correct") val quizCorrect: Int
)

@Serializable
data class DeviceDashboardView(
    val day: String,
    @kotlinx.serialization.SerialName("today_minutes") val todayMinutes: Int,
    @kotlinx.serialization.SerialName("week_minutes") val weekMinutes: Int,
    @kotlinx.serialization.SerialName("app_usage") val appUsage: List<DashboardAppUsageView>,
    @kotlinx.serialization.SerialName("quiz_attempts") val quizAttempts: Int,
    @kotlinx.serialization.SerialName("quiz_questions") val quizQuestions: Int,
    @kotlinx.serialization.SerialName("quiz_correct") val quizCorrect: Int,
    @kotlinx.serialization.SerialName("quiz_accuracy_percent") val quizAccuracyPercent: Int,
    @kotlinx.serialization.SerialName("rewards_granted") val rewardsGranted: Int,
    @kotlinx.serialization.SerialName("current_app") val currentApp: String?,
    @kotlinx.serialization.SerialName("current_app_label") val currentAppLabel: String?,
    @kotlinx.serialization.SerialName("current_app_observed_at") val currentAppObservedAt: String?,
    @kotlinx.serialization.SerialName("last_usage_at") val lastUsageAt: String?,
    @kotlinx.serialization.SerialName("last_seen_at") val lastSeenAt: String?,
    @kotlinx.serialization.SerialName("policy_revision") val policyRevision: Long,
    val health: JsonObject,
    val days: List<DashboardDayView>
)

@Serializable
data class DeviceAppPayload(
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    val label: String,
    val category: Int = 0,
    val enabled: Boolean = true
)

@Serializable
data class DeviceAppsRequest(val apps: List<DeviceAppPayload>)

@Serializable
data class DeviceAppView(
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    val label: String,
    val category: Int,
    val enabled: Boolean,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String
)

@Serializable
data class GrantCreateRequest(
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    val minutes: Int,
    @kotlinx.serialization.SerialName("expires_in_minutes") val expiresInMinutes: Int = 1440
)

@Serializable
data class GrantView(
    val id: String,
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    val minutes: Int,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("applied") val applied: Boolean,
    @kotlinx.serialization.SerialName("applied_at") val appliedAt: String?,
    @kotlinx.serialization.SerialName("revoked") val revoked: Boolean,
    @kotlinx.serialization.SerialName("revoked_at") val revokedAt: String?,
    @kotlinx.serialization.SerialName("revoked_by") val revokedBy: String?
)

@Serializable
data class ScreenshotUploadRequest(
    @kotlinx.serialization.SerialName("call_id") val callId: String,
    @kotlinx.serialization.SerialName("image_base64") val imageBase64: String,
    val width: Int,
    val height: Int
)

@Serializable
data class ScreenshotView(
    val id: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("call_id") val callId: String,
    val width: Int,
    val height: Int,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String
)

@Serializable
data class StoredUsage(
    @kotlinx.serialization.SerialName("app_minutes") val appMinutes: Map<String, Int>,
    val health: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class PolicyAckRequest(val revision: Long, val verified: Boolean, val errors: List<String> = emptyList())

@Serializable
data class DeviceAuditRequest(val event: String, val detail: String = "")

@Serializable
data class HelpRequestCreateRequest(
    @kotlinx.serialization.SerialName("client_request_id") val clientRequestId: String,
    @kotlinx.serialization.SerialName("target_package") val targetPackage: String = "",
    @kotlinx.serialization.SerialName("trigger_id") val triggerId: String = "",
    val reason: String,
    val message: String = ""
)

@Serializable
data class HelpRequestRespondRequest(val reply: String)

@Serializable
data class HelpRequestView(
    val id: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("client_request_id") val clientRequestId: String,
    @kotlinx.serialization.SerialName("target_package") val targetPackage: String,
    @kotlinx.serialization.SerialName("trigger_id") val triggerId: String,
    val reason: String,
    val message: String,
    val status: String,
    @kotlinx.serialization.SerialName("parent_reply") val parentReply: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("answered_at") val answeredAt: String?,
    @kotlinx.serialization.SerialName("acknowledged_at") val acknowledgedAt: String?
)

@Serializable
data class CallCreateRequest(val kind: String)

@Serializable
data class CallRespondRequest(val accept: Boolean)

@Serializable
data class CallView(
    val id: String,
    val device_id: String,
    val kind: String,
    val status: String,
    val created_at: String,
    val expires_at: String,
    val ended_at: String?
)

@Serializable
data class LiveKitResponse(
    val url: String,
    val token: String,
    val room: String,
    val expires_at: Long
)

@Serializable
data class AuditView(
    val id: String,
    val actor: String,
    val event: String,
    val detail: String,
    val created_at: String
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class HealthResponse(val status: String, val database: Boolean, val version: String)

@Serializable
data class PolicyKeyResponse(val key_id: String, val public_key: String)

@Serializable
data class QuizQuestionResult(
    @kotlinx.serialization.SerialName("question_id") val questionId: String,
    val correct: Boolean,
    val type: String = "",
    val subject: String = "",
    val grade: Int = 0,
    val difficulty: String = "",
    val language: String = "",
    val tags: List<String> = emptyList()
)

@Serializable
data class QuizAttemptResultRequest(
    @kotlinx.serialization.SerialName("attempt_id") val attemptId: String,
    @kotlinx.serialization.SerialName("occurred_at") val occurredAt: Long,
    @kotlinx.serialization.SerialName("target_package") val targetPackage: String,
    @kotlinx.serialization.SerialName("trigger_id") val triggerId: String? = null,
    @kotlinx.serialization.SerialName("policy_revision") val policyRevision: Long,
    @kotlinx.serialization.SerialName("quiz_version") val quizVersion: Long,
    @kotlinx.serialization.SerialName("reward_granted") val rewardGranted: Boolean,
    val questions: List<QuizQuestionResult>
)

@Serializable
data class QuizAttemptResultView(
    val id: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("attempt_id") val attemptId: String,
    @kotlinx.serialization.SerialName("occurred_at") val occurredAt: String,
    @kotlinx.serialization.SerialName("target_package") val targetPackage: String,
    @kotlinx.serialization.SerialName("trigger_id") val triggerId: String?,
    @kotlinx.serialization.SerialName("policy_revision") val policyRevision: Long,
    @kotlinx.serialization.SerialName("quiz_version") val quizVersion: Long,
    @kotlinx.serialization.SerialName("question_count") val questionCount: Int,
    @kotlinx.serialization.SerialName("correct_count") val correctCount: Int,
    @kotlinx.serialization.SerialName("reward_granted") val rewardGranted: Boolean,
    val questions: List<QuizQuestionResult>,
    @kotlinx.serialization.SerialName("received_at") val receivedAt: String
)

@Serializable
data class NoticeCreateRequest(val kind: String, val message: String = "")

@Serializable
data class NoticeView(
    val id: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    val kind: String,
    val message: String,
    val status: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("acknowledged_at") val acknowledgedAt: String?
)

@Serializable
data class ManualQuizTriggerCreateRequest(
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    @kotlinx.serialization.SerialName("expires_in_minutes") val expiresInMinutes: Int = 10
)

@Serializable
data class ManualQuizTriggerAckRequest(val status: String, val detail: String = "")

@Serializable
data class ManualQuizTriggerView(
    val id: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("package_name") val packageName: String,
    val status: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("delivered_at") val deliveredAt: String?,
    @kotlinx.serialization.SerialName("finished_at") val finishedAt: String?,
    val detail: String
)
