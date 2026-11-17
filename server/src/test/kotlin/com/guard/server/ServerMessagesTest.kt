package com.guard.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerMessagesTest {

    @Test
    fun exactMessagesAreLocalized() {
        assertEquals("需要登录", ServerMessages.localize("Authentication required"))
        assertEquals("未配置 LiveKit", ServerMessages.localize("LiveKit is not configured"))
        assertEquals("设备名称必须为 1 到 80 个字符",
            ServerMessages.localize("Device name must be 1 to 80 characters"))
        assertEquals("无法记录通话结束状态",
            ServerMessages.localize("Call end state could not be recorded"))
    }

    @Test
    fun policyFieldMessagesAreLocalized() {
        assertEquals("时区 必须是字符串", ServerMessages.localize("timezone must be a string"))
        assertEquals("题库科目 必须包含字符串", ServerMessages.localize("quiz_subjects must contain strings"))
        assertEquals("星期 的值必须介于 1 到 7 之间",
            ServerMessages.localize("days values must be between 1 and 7"))
        assertEquals("题库难度 包含无效值",
            ServerMessages.localize("quiz_difficulties contains an invalid value"))
        assertEquals("未知策略字段：unknown",
            ServerMessages.localize("Unknown policy fields: unknown"))
    }

    @Test
    fun questionMessagesAreLocalized() {
        assertEquals("题目 3 的ID 必须为 1 到 128 个字符",
            ServerMessages.localize("Question 3 id must be 1 to 128 characters"))
        assertEquals("题目 2 包含未知字段：evil",
            ServerMessages.localize("Question 2 has an unknown field: evil"))
        assertEquals("题目 1 的年级必须介于 0 到 12 之间",
            ServerMessages.localize("Question 1 grade must be between 0 and 12"))
        assertEquals("题目 4 的选项不能重复",
            ServerMessages.localize("Question 4 options must be distinct"))
        assertEquals("题目 5 的图片必须为 4 字节到 512 KiB",
            ServerMessages.localize("Question 5 image must be 4 bytes to 512 KiB"))
    }

    @Test
    fun quizEnvelopeAndQuantityMessagesAreLocalized() {
        assertEquals("题库必须包含 1 到 500 道题",
            ServerMessages.localize("Quiz pack needs 1 to 500 questions"))
        assertEquals("题库增量需要 1 到 500 个操作",
            ServerMessages.localize("Quiz delta needs 1 to 500 operations"))
        assertEquals("题库信封 必须是 JSON 对象",
            ServerMessages.localize("Quiz envelope must be a JSON object"))
        assertEquals("题库增量内容 不是有效 JSON",
            ServerMessages.localize("Quiz delta payload is not valid JSON"))
    }
}
