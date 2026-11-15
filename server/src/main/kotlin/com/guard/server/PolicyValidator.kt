package com.guard.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

object PolicyValidator {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val PHONE = Regex("^\\+?[0-9][0-9 ()-]{0,23}$")
    private val ALLOWED = setOf(
        "schema", "revision", "timezone", "enabled", "launcher_enabled",
        "quiz_gate_enabled", "reward_minutes", "max_attempts_per_day",
        "cooldown_seconds", "budget_warning_minutes",
        "continuous_use_reminder_minutes", "quiz_question_count", "quiz_subjects", "quiz_grades",
        "quiz_difficulties", "quiz_languages", "quiz_tags",
        "quiz_triggers", "help_contacts",
        "daily_budgets", "blocked_packages", "always_allowed_packages", "schedules"
    )

    fun validateAndBuild(input: JsonObject, revision: Long): JsonObject {
        val unknown = input.keys - ALLOWED
        if (unknown.isNotEmpty()) badRequest("Unknown policy fields: ${unknown.sorted().joinToString()}")
        if (integer(input, "schema", 1, 1) != 1) badRequest("Unsupported policy schema")
        val timezone = string(input, "timezone", 64)
        try {
            ZoneId.of(timezone)
        } catch (exception: Exception) {
            badRequest("timezone must be an IANA zone id")
        }
        val enabled = boolean(input, "enabled")
        val launcherEnabled = boolean(input, "launcher_enabled")
        val quizGateEnabled = boolean(input, "quiz_gate_enabled")
        val rewardMinutes = integer(input, "reward_minutes", 0, 1440)
        val maxAttempts = integer(input, "max_attempts_per_day", 0, 1000)
        val cooldownSeconds = integer(input, "cooldown_seconds", 0, 86400)
        val budgetWarningMinutes = optionalInteger(input, "budget_warning_minutes", 0, 0, 1440)
        val continuousUseReminderMinutes = optionalInteger(
            input, "continuous_use_reminder_minutes", 0, 0, 1440
        )
        val quizQuestionCount = optionalInteger(input, "quiz_question_count", 1, 1, 10)
        val quizSubjects = stringSet(input, "quiz_subjects", 32, 64)
        val quizGrades = intSet(input, "quiz_grades", 0, 12, 13)
        val quizDifficulties = stringSet(input, "quiz_difficulties", 3, 16).map {
            if (it !in setOf("easy", "medium", "hard")) {
                badRequest("quiz_difficulties may contain easy, medium, or hard")
            }
            it
        }
        val quizLanguages = stringSet(input, "quiz_languages", 32, 32).map {
            if (!it.matches(Regex("^[a-z]{2,8}(?:-[a-z0-9]{1,8})*$"))) {
                badRequest("quiz_languages must contain BCP-47 language tags")
            }
            it
        }
        val quizTags = stringSet(input, "quiz_tags", 64, 48)

        val budgetSource = objectValue(input, "daily_budgets")
        if (budgetSource.size > 200) badRequest("At most 200 daily budgets are supported")
        val budgets = buildJsonObject {
            budgetSource.keys.sorted().forEach { packageName ->
                requirePackage(packageName)
                put(packageName, integer(budgetSource, packageName, 0, 1440))
            }
        }
        val blockedSource = arrayValue(input, "blocked_packages")
        if (blockedSource.size > 500) badRequest("At most 500 blocked packages are supported")
        val blocked = blockedSource.map {
            val packageName = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: badRequest("blocked_packages must contain strings")
            requirePackage(packageName)
            packageName
        }
        if (blocked.toSet().size != blocked.size) badRequest("blocked_packages contains duplicates")
        val alwaysAllowedSource = optionalArray(input, "always_allowed_packages")
            ?: JsonArray(emptyList())
        if (alwaysAllowedSource.size > 500) {
            badRequest("At most 500 always-allowed packages are supported")
        }
        val alwaysAllowed = alwaysAllowedSource.map {
            val packageName = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: badRequest("always_allowed_packages must contain strings")
            requirePackage(packageName)
            packageName
        }.sorted()
        if (alwaysAllowed.toSet().size != alwaysAllowed.size) {
            badRequest("always_allowed_packages contains duplicates")
        }
        if (alwaysAllowed.any { it in blocked.toSet() }) {
            badRequest("A package cannot be both always allowed and always blocked")
        }

        val schedulesSource = input["schedules"]?.let {
            it as? JsonObject ?: badRequest("schedules must be an object")
        }
        if (schedulesSource != null && schedulesSource.size > 100) {
            badRequest("At most 100 scheduled packages are supported")
        }
        val schedules = schedulesSource?.let { source ->
            buildJsonObject {
                source.keys.sorted().forEach { packageName ->
                    requirePackage(packageName)
                    val windows = source[packageName] as? JsonArray
                        ?: badRequest("Each schedule must be an array")
                    if (windows.isEmpty() || windows.size > 21) {
                        badRequest("A scheduled package needs 1 to 21 windows")
                    }
                    put(packageName, buildJsonArray {
                        windows.forEach { window ->
                            add(validateWindow(window))
                        }
                    })
                }
            }
        }
        val managedPackages = budgetSource.keys + (schedulesSource?.keys ?: emptySet())
        val quizTriggers = validateQuizTriggers(
            input, managedPackages, blocked.toSet(), alwaysAllowed.toSet()
        )
        val helpContacts = validateHelpContacts(input)

        return buildJsonObject {
            put("schema", 1)
            put("revision", revision)
            put("timezone", timezone)
            put("enabled", enabled)
            put("launcher_enabled", launcherEnabled)
            put("quiz_gate_enabled", quizGateEnabled)
            put("reward_minutes", rewardMinutes)
            put("max_attempts_per_day", maxAttempts)
            put("cooldown_seconds", cooldownSeconds)
            put("budget_warning_minutes", budgetWarningMinutes)
            put("continuous_use_reminder_minutes", continuousUseReminderMinutes)
            put("quiz_question_count", quizQuestionCount)
            put("quiz_subjects", JsonArray(quizSubjects.map { JsonPrimitive(it) }))
            put("quiz_grades", JsonArray(quizGrades.map { JsonPrimitive(it) }))
            put("quiz_difficulties", JsonArray(quizDifficulties.map { JsonPrimitive(it) }))
            put("quiz_languages", JsonArray(quizLanguages.map { JsonPrimitive(it) }))
            put("quiz_tags", JsonArray(quizTags.map { JsonPrimitive(it) }))
            put("quiz_triggers", quizTriggers)
            put("help_contacts", helpContacts)
            put("daily_budgets", budgets)
            put("blocked_packages", JsonArray(blocked.map { JsonPrimitive(it) }))
            put("always_allowed_packages", JsonArray(alwaysAllowed.map { JsonPrimitive(it) }))
            if (schedules != null) put("schedules", schedules)
        }
    }

    private fun validateHelpContacts(input: JsonObject): JsonArray {
        val source = optionalArray(input, "help_contacts") ?: return JsonArray(emptyList())
        if (source.size > 3) badRequest("At most 3 help contacts are supported")
        val seen = HashSet<String>()
        return buildJsonArray {
            source.forEach { element ->
                val contact = element as? JsonObject
                    ?: badRequest("Each help contact must be an object")
                val allowed = setOf("label", "phone")
                val unknown = contact.keys - allowed
                if (unknown.isNotEmpty()) {
                    badRequest("Unknown help contact fields: ${unknown.sorted().joinToString()}")
                }
                val labelSource = (contact["label"] as? JsonPrimitive)
                    ?.takeIf { value -> value.isString }?.content
                    ?: badRequest("help contact label must be a string")
                val phoneSource = (contact["phone"] as? JsonPrimitive)
                    ?.takeIf { value -> value.isString }?.content
                    ?: badRequest("help contact phone must be a string")
                val label = labelSource.trim()
                val phone = phoneSource.trim()
                if (label.isEmpty() || label.length > 40
                        || label.any { character -> character.isISOControl() }) {
                    badRequest("help contact label must be 1 to 40 printable characters")
                }
                if (phone.length > 24 || !PHONE.matches(phone)) {
                    badRequest("help contact phone must be a dialable number")
                }
                val digits = phone.count { character -> character.isDigit() }
                if (digits !in 2..20) {
                    badRequest("help contact phone must contain 2 to 20 digits")
                }
                val key = label + "\u0000" + phone
                if (!seen.add(key)) badRequest("help_contacts contains duplicates")
                add(buildJsonObject {
                    put("label", label)
                    put("phone", phone)
                })
            }
        }
    }

    private fun validateQuizTriggers(input: JsonObject, managedPackages: Set<String>,
            blockedPackages: Set<String>, alwaysAllowedPackages: Set<String>): JsonArray {
        val source = optionalArray(input, "quiz_triggers") ?: return JsonArray(emptyList())
        if (source.size > 32) badRequest("At most 32 quiz triggers are supported")
        val seen = HashSet<String>()
        return buildJsonArray {
            source.forEach { element ->
                val trigger = element as? JsonObject
                    ?: badRequest("Each quiz trigger must be an object")
                val type = string(trigger, "type", 32)
                val allowed = when (type) {
                    "total_usage" -> setOf("type", "packages", "minutes")
                    "app_enter" -> setOf("type", "packages", "delay_seconds")
                    "continuous_use" -> setOf("type", "packages", "minutes")
                    "schedule_start" -> setOf("type", "packages", "days", "start_minute")
                    else -> badRequest("Unsupported quiz trigger type: $type")
                }
                val unknown = trigger.keys - allowed
                if (unknown.isNotEmpty()) {
                    badRequest("Unknown quiz trigger fields: ${unknown.sorted().joinToString()}")
                }
                val packages = triggerPackages(trigger)
                if (packages.any { it !in managedPackages }) {
                    badRequest("Quiz trigger packages must also have a daily budget or schedule")
                }
                if (packages.any { it in blockedPackages }) {
                    badRequest("An always-blocked package cannot be used as a quiz trigger target")
                }
                if (packages.any { it in alwaysAllowedPackages }) {
                    badRequest("An always-allowed package cannot be used as a quiz trigger target")
                }
                val minutes = if (type == "total_usage" || type == "continuous_use") {
                    integer(trigger, "minutes", 1, 1440)
                } else 0
                val delaySeconds = if (type == "app_enter") {
                    optionalInteger(trigger, "delay_seconds", 0, 0, 3600)
                } else 0
                val days = if (type == "schedule_start") dayArray(trigger, "days") else emptyList()
                val startMinute = if (type == "schedule_start") {
                    integer(trigger, "start_minute", 0, 1439)
                } else 0
                val normalized = buildJsonObject {
                    put("type", type)
                    put("packages", JsonArray(packages.map { JsonPrimitive(it) }))
                    when (type) {
                        "total_usage", "continuous_use" -> put("minutes", minutes)
                        "app_enter" -> put("delay_seconds", delaySeconds)
                        "schedule_start" -> {
                            put("days", JsonArray(days.map { JsonPrimitive(it) }))
                            put("start_minute", startMinute)
                        }
                    }
                }
                val id = quizTriggerId(type, packages, minutes, delaySeconds, days, startMinute)
                if (!seen.add(id)) badRequest("Duplicate quiz trigger configuration")
                add(buildJsonObject {
                    put("id", id)
                    normalized.forEach { (key, value) -> put(key, value) }
                })
            }
        }
    }

    private fun triggerPackages(trigger: JsonObject): List<String> {
        val array = trigger["packages"] as? JsonArray
            ?: badRequest("Each quiz trigger needs a packages array")
        if (array.size !in 1..50) badRequest("A quiz trigger needs 1 to 50 packages")
        val packages = array.map {
            val packageName = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: badRequest("Quiz trigger packages must contain strings")
            requirePackage(packageName)
            packageName
        }.sorted()
        if (packages.toSet().size != packages.size) badRequest("Quiz trigger packages contain duplicates")
        return packages
    }

    private fun dayArray(trigger: JsonObject, name: String): List<Int> {
        val array = trigger[name] as? JsonArray ?: badRequest("$name must be an array")
        if (array.isEmpty()) badRequest("$name needs at least one day")
        if (array.size > 7) badRequest("$name contains too many days")
        val days = array.map {
            val value = (it as? JsonPrimitive)?.intOrNull ?: badRequest("$name must contain integers")
            if (value !in 1..7) badRequest("$name values must be between 1 and 7")
            value
        }.sorted()
        if (days.toSet().size != days.size) badRequest("$name contains duplicates")
        return days
    }

    private fun quizTriggerId(type: String, packages: List<String>, minutes: Int,
            delaySeconds: Int, days: List<Int>, startMinute: Int): String {
        val canonical = "guard-quiz-trigger-v1\n$type\n${packages.joinToString(",")}\n" +
            "$minutes\n$delaySeconds\n${days.joinToString(",")}\n$startMinute"
        return "quiz-trigger-" + digestHex("SHA-256",
            canonical.toByteArray(Charsets.UTF_8)).take(24)
    }

    fun validateUsage(day: String, apps: Map<String, Int>, health: JsonObject) {
        try {
            LocalDate.parse(day)
        } catch (exception: Exception) {
            badRequest("day must be an ISO-8601 date")
        }
        if (apps.size > 500) badRequest("At most 500 app usage entries are supported")
        apps.forEach { (packageName, minutes) ->
            requirePackage(packageName)
            if (minutes !in 0..1440) badRequest("Usage minutes must be between 0 and 1440")
        }
        if (health.toString().length > 16_384) badRequest("health payload is too large")
    }

    private fun validateWindow(element: JsonElement): JsonObject {
        val window = element as? JsonObject ?: badRequest("Each schedule window must be an object")
        val allowed = setOf("days", "start_minute", "duration_minutes")
        if (window.keys != allowed) badRequest("A window needs exactly days, start_minute and duration_minutes")
        val days = window["days"] as? JsonArray ?: badRequest("days must be an array")
        if (days.isEmpty()) badRequest("A window needs at least one day")
        val normalized = days.map {
            val value = (it as? JsonPrimitive)?.intOrNull
                ?: badRequest("days must contain integers")
            if (value !in 1..7) badRequest("Day values must be 1 (Monday) to 7 (Sunday)")
            value
        }
        if (normalized.toSet().size != normalized.size) badRequest("days contains duplicates")
        val start = integer(window, "start_minute", 0, 1439)
        val duration = integer(window, "duration_minutes", 1, 1440)
        return buildJsonObject {
            put("days", JsonArray(normalized.sorted().map { JsonPrimitive(it) }))
            put("start_minute", start)
            put("duration_minutes", duration)
        }
    }

    fun requirePackage(packageName: String) {
        if (packageName.length > 255 || !PACKAGE.matches(packageName)) {
            badRequest("Invalid Android package name: $packageName")
        }
    }

    private fun integer(source: JsonObject, name: String, min: Int, max: Int): Int {
        val value = (source[name] as? JsonPrimitive)
            ?.takeIf { primitive -> !primitive.isString }?.intOrNull
            ?: badRequest("$name must be an integer")
        if (value !in min..max) badRequest("$name must be between $min and $max")
        return value
    }

    private fun optionalInteger(source: JsonObject, name: String, default: Int,
            min: Int, max: Int): Int {
        if (source[name] == null) return default
        return integer(source, name, min, max)
    }

    private fun stringSet(source: JsonObject, name: String, maxItems: Int, maxLength: Int): List<String> {
        val array = optionalArray(source, name) ?: return emptyList()
        if (array.size > maxItems) badRequest("$name contains too many values")
        val values = array.map {
            val text = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: badRequest("$name must contain strings")
            if (text.isEmpty() || text.length > maxLength || text.any { character -> character.isISOControl() }) {
                badRequest("$name contains an invalid value")
            }
            text.trim().lowercase(java.util.Locale.ROOT)
        }
        if (values.toSet().size != values.size) badRequest("$name contains duplicates")
        return values.sorted()
    }

    private fun intSet(source: JsonObject, name: String, min: Int, max: Int,
            maxItems: Int): List<Int> {
        val array = optionalArray(source, name) ?: return emptyList()
        if (array.size > maxItems) badRequest("$name contains too many values")
        val values = array.map {
            val value = (it as? JsonPrimitive)?.intOrNull
                ?: badRequest("$name must contain integers")
            if (value !in min..max) badRequest("$name values must be between $min and $max")
            value
        }
        if (values.toSet().size != values.size) badRequest("$name contains duplicates")
        return values.sorted()
    }

    private fun optionalArray(source: JsonObject, name: String): JsonArray? {
        val value = source[name] ?: return null
        return value as? JsonArray ?: badRequest("$name must be an array")
    }

    private fun boolean(source: JsonObject, name: String): Boolean =
        (source[name] as? JsonPrimitive)?.booleanOrNull
            ?: badRequest("$name must be a boolean")

    private fun string(source: JsonObject, name: String, max: Int): String {
        val value = (source[name] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: badRequest("$name must be a string")
        if (value.isEmpty() || value.length > max) badRequest("$name has an invalid length")
        return value
    }

    private fun objectValue(source: JsonObject, name: String): JsonObject =
        source[name] as? JsonObject ?: badRequest("$name must be an object")

    private fun arrayValue(source: JsonObject, name: String): JsonArray =
        source[name] as? JsonArray ?: badRequest("$name must be an array")
}
