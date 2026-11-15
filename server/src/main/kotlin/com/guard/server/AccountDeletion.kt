package com.guard.server

import java.sql.Connection

class AccountDeletionService(
    private val database: Database,
    private val passwordHasher: PasswordHasher,
    private val dummyHash: String
) {
    fun delete(
        parentId: String,
        email: String,
        password: String,
        confirmation: String
    ): Boolean = database.transaction { connection ->
        val normalizedEmail = normalizeEmailOrNull(email)
        val parent = connection.prepareStatement(
            "SELECT email, password_hash FROM parents WHERE id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows ->
                if (rows.next()) ParentRow(
                    parentId,
                    rows.getString("email"),
                    rows.getString("password_hash")
                ) else null
            }
        }
        val passwordMatches = passwordHasher.verify(
            password,
            parent?.passwordHash ?: dummyHash
        )
        if (parent == null || normalizedEmail == null || parent.email != normalizedEmail
            || confirmation != "DELETE" || !passwordMatches
        ) {
            return@transaction false
        }

        deleteDeviceData(connection, parentId)
        deleteParentData(connection, parentId)
        val deletedParents = connection.prepareStatement(
            "DELETE FROM parents WHERE id = ?"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeUpdate()
        }
        deletedParents == 1
    }

    private fun deleteDeviceData(connection: Connection, parentId: String) {
        val deviceIds = connection.prepareStatement(
            "SELECT id FROM devices WHERE parent_id = ?"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("id"))
                }
            }
        }
        for (deviceId in deviceIds) {
            for (table in DEVICE_TABLES) {
                connection.prepareStatement("DELETE FROM $table WHERE device_id = ?").use { statement ->
                    statement.setString(1, deviceId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun deleteParentData(connection: Connection, parentId: String) {
        for (table in PARENT_TABLES) {
            connection.prepareStatement("DELETE FROM $table WHERE parent_id = ?").use { statement ->
                statement.setString(1, parentId)
                statement.executeUpdate()
            }
        }
    }

    private fun normalizeEmailOrNull(email: String): String? = try {
        ParentRepository.normalizeEmail(email)
    } catch (exception: ApiException) {
        null
    }

    private companion object {
        val DEVICE_TABLES = listOf(
            "screenshots",
            "quiz_attempt_results",
            "device_notices",
            "temporary_grants",
            "device_apps",
            "call_requests",
            "audit_events",
            "usage_reports",
            "policy_revisions",
            "manual_quiz_triggers",
            "help_requests"
        )

        val PARENT_TABLES = listOf(
            "parent_sessions",
            "manual_quiz_triggers",
            "help_requests",
            "quiz_key_transitions",
            "quiz_upstream_events",
            "quiz_upstream_sources",
            "quiz_packs",
            "devices"
        )
    }
}
