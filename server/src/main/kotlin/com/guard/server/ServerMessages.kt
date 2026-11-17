package com.guard.server

internal object ServerMessages {
    private val exact = mapOf(
        "Authentication required" to "需要登录",
        "Not allowed" to "不允许",
        "Not found" to "未找到",
        "Invalid request" to "请求无效",
        "Internal server error" to "服务器内部错误",
        "Too many requests; try again shortly" to "请求过于频繁，请稍后重试",
        "A valid email address is required" to "请输入有效邮箱地址",
        "Invalid email or password" to "邮箱或密码错误",
        "Password must be at least 10 characters" to "密码至少需要 10 个字符",
        "An account with this email already exists" to "此邮箱已存在账户",
        "Account deletion confirmation failed" to "账户删除确认失败",
        "Account no longer exists" to "账户已不存在",
        "Session not found" to "未找到会话",
        "Invalid or expired token" to "令牌无效或已过期",
        "Parent session has been revoked or expired" to "家长会话已撤销或过期",
        "This device is already paired" to "此设备已配对",
        "This device has been revoked" to "此设备已被撤销",
        "Device has been revoked" to "设备已被撤销",
        "Device has not confirmed pairing yet" to "设备尚未确认配对",
        "Device is not paired yet" to "设备尚未配对",
        "Device is not yet paired" to "设备尚未配对",
        "Device is not registered" to "设备尚未注册",
        "Device token has been revoked" to "设备令牌已撤销",
        "Device token is no longer valid" to "设备令牌已失效",
        "Device not found" to "未找到设备",
        "Device key must be an EC public key" to "设备密钥必须是 EC 公钥",
        "Device key must use P-256" to "设备密钥必须使用 P-256",
        "Device public key is invalid" to "设备公钥无效",
        "Device public key is not valid X.509 base64" to "设备公钥不是有效的 X.509 Base64",
        "Device proof has expired" to "设备证明已过期",
        "Device proof signature is invalid" to "设备证明签名无效",
        "Device proof was already used" to "设备证明已被使用",
        "Pairing code is invalid or expired" to "配对码无效或已过期",
        "Unsupported platform" to "不支持的平台",
        "Too many errors" to "错误次数过多",
        "No policy published" to "尚未发布策略",
        "The active policy cannot be read" to "无法读取当前策略",
        "Unsupported policy schema" to "不支持的策略格式",
        "Guard is disabled in the active policy" to "当前策略已禁用守护",
        "The quiz gate is disabled in the active policy" to "当前策略已禁用答题门禁",
        "The package is not managed by the active policy" to "当前策略未管理该应用",
        "The package is always blocked" to "该应用已被始终阻止",
        "The package is always allowed" to "该应用已被始终允许",
        "Publish a signed policy before raising a quiz" to "请先发布已签名策略再发起答题",
        "Publish a signed policy before creating an allowance" to "请先发布已签名策略再创建临时许可",
        "A package cannot be both always allowed and always blocked" to "同一应用不能同时设为始终允许和始终阻止",
        "Duplicate quiz trigger configuration" to "重复的答题触发配置",
        "Call request not found" to "未找到通话请求",
        "Call request has expired" to "通话请求已过期",
        "Call request has not been accepted" to "通话请求尚未被接受",
        "Call request is no longer pending" to "通话请求已不再等待处理",
        "The child has not accepted this request" to "孩子尚未接受此请求",
        "Only an accepted call can be ended" to "只有已接受的通话才能结束",
        "Unsupported call kind" to "不支持的通话类型",
        "Screenshot not found" to "未找到截图",
        "Screenshot request not found" to "未找到截图请求",
        "Screenshot request has expired" to "截图请求已过期",
        "Screenshot request has not been accepted" to "截图请求尚未被接受",
        "Screenshot requests do not use LiveKit" to "截图请求不使用 LiveKit",
        "Screenshot dimensions are out of range" to "截图尺寸超出范围",
        "Screenshot image is too large" to "截图图片过大",
        "Screenshot image is not valid base64" to "截图图片不是有效的 Base64",
        "Screenshot must be a JPEG image up to 2 MiB" to "截图必须是最大 2 MiB 的 JPEG 图片",
        "Temporary grant not found" to "未找到临时许可",
        "Temporary grant has expired" to "临时许可已过期",
        "Temporary grant has been revoked" to "临时许可已撤销",
        "Usage history is limited to 90 days" to "使用历史最多查询 90 天",
        "Usage minutes must be between 0 and 1440" to "使用分钟数必须介于 0 到 1440 之间",
        "Date parameters must use ISO-8601 dates" to "日期参数必须使用 ISO-8601 格式",
        "from must not be after to" to "开始时间不能晚于结束时间",
        "after must not be negative" to "after 不能为负数",
        "Policy revision not found" to "未找到策略修订",
        "Policy revision must be a positive integer" to "策略修订号必须是正整数",
        "Policy and quiz versions must not be negative" to "策略和题库版本不能为负数",
        "Notice is no longer pending" to "通知已不再等待处理",
        "Notice message must be at most 200 characters" to "通知留言最多 200 个字符",
        "Unsupported notice kind" to "不支持的通知类型",
        "Help request not found" to "未找到求助请求",
        "Help request not found or expired" to "未找到求助请求或请求已过期",
        "Help message must be at most 500 characters" to "求助留言最多 500 个字符",
        "A help reply must be 1 to 1000 characters" to "求助回复必须为 1 到 1000 个字符",
        "Quiz trigger is no longer pending" to "答题触发已不再等待处理",
        "Unsupported quiz trigger status" to "不支持的答题触发状态",
        "Quiz pack not found" to "未找到题库",
        "A quiz pack with this version already exists" to "此版本的题库已存在",
        "Cannot revoke a quiz key that signs the active quiz pack" to "无法撤销正在签署启用题库的密钥",
        "The old signing key is not known to this account" to "此账户不认识旧签名密钥",
        "The delta base version does not exist" to "增量基础版本不存在",
        "A delta must be signed by the same key as its base pack" to "增量必须由基础题库的同一密钥签名",
        "base_version is required with delta_envelope" to "使用增量信封时必须提供基础版本",
        "base_version must be a positive integer" to "基础版本必须是正整数",
        "Upstream title must be 1 to 120 characters" to "上游标题必须为 1 到 120 个字符",
        "Upstream sync interval must be between 15 and 1440 minutes" to "上游同步间隔必须介于 15 到 1440 分钟之间",
        "Event limit must be between 1 and 100" to "事件数量限制必须介于 1 到 100 之间",
        "Key transition not found" to "未找到密钥转换",
        "Key transition is missing or too large" to "密钥转换缺失或过大",
        "Key transition is not valid JSON" to "密钥转换不是有效 JSON",
        "Key transition ids are invalid" to "密钥转换 ID 无效",
        "Key transition type is not supported" to "不支持的密钥转换类型",
        "Key transition is outside the accepted time window" to "密钥转换不在允许的时间窗口内",
        "Key transition new_key_id does not match the public key" to "密钥转换的新密钥 ID 与公钥不匹配",
        "Key transition signature does not match the old key" to "密钥转换签名与旧密钥不匹配",
        "Key transition signature is malformed" to "密钥转换签名格式错误",
        "Key transition signature is not valid base64" to "密钥转换签名不是有效 Base64",
        "Unsupported key transition schema" to "不支持的密钥转换格式",
        "Quiz pack title must be 1 to 120 characters" to "题库标题必须为 1 到 120 个字符",
        "Quiz pack version is out of range" to "题库版本超出范围",
        "Quiz pack was signed by an untrusted key" to "题库由不受信任的密钥签名",
        "Quiz delta was signed by an untrusted key" to "题库增量由不受信任的密钥签名",
        "Quiz envelope is missing or too large" to "题库签名信封缺失或过大",
        "Quiz payload is missing or too large" to "题库内容缺失或过大",
        "Quiz delta envelope is missing or too large" to "题库增量信封缺失或过大",
        "Quiz delta payload is missing or too large" to "题库增量内容缺失或过大",
        "Quiz delta operations must be an array" to "题库增量操作必须是数组",
        "Quiz delta operation must be an object" to "题库增量操作必须是对象",
        "Quiz delta operation is not supported" to "不支持的题库增量操作",
        "Quiz delta put needs a question object" to "题库增量的 put 操作需要题目对象",
        "Quiz delta removes a question missing from the base pack" to "题库增量删除了基础题库中不存在的题目",
        "Quiz delta published_at must not be empty" to "题库增量的发布时间不能为空",
        "Quiz delta base_version does not match the base envelope" to "题库增量的基础版本与基础信封不匹配",
        "Quiz delta target version does not match the full pack" to "题库增量的目标版本与完整题库不匹配",
        "Quiz delta result version is inconsistent" to "题库增量结果版本不一致",
        "Quiz delta versions are out of order" to "题库增量版本顺序错误",
        "Quiz delta result does not match the full target pack" to "题库增量结果与完整目标题库不匹配",
        "Quiz delta published_at does not match the full target pack" to "题库增量发布时间与完整目标题库不匹配",
        "Quiz delta signature does not match the payload" to "题库增量签名与内容不匹配",
        "Quiz delta signature is malformed" to "题库增量签名格式错误",
        "Quiz delta signature is not valid base64" to "题库增量签名不是有效 Base64",
        "Quiz published_at must not be empty" to "题库发布时间不能为空",
        "Quiz signature does not match the payload" to "题库签名与内容不匹配",
        "Quiz signature is malformed" to "题库签名格式错误",
        "Quiz signature is not valid base64" to "题库签名不是有效 Base64",
        "Quiz public key has an invalid length" to "题库公钥长度无效",
        "Quiz public key is not a valid EC public key" to "题库公钥不是有效的 EC 公钥",
        "Quiz public key must be an EC key" to "题库公钥必须是 EC 密钥",
        "Quiz payload questions must be an array" to "题库内容的题目必须是数组",
        "Quiz base payload questions must be an array" to "题库基础内容的题目必须是数组",
        "Quiz target payload questions must be an array" to "题库目标内容的题目必须是数组",
        "Quiz base question must be an object" to "题库基础题目必须是对象",
        "Quiz target question must be an object" to "题库目标题目必须是对象",
        "Unsupported quiz envelope schema" to "不支持的题库信封格式",
        "Unsupported quiz payload schema" to "不支持的题库内容格式",
        "Unsupported quiz delta envelope schema" to "不支持的题库增量信封格式",
        "Unsupported quiz delta payload schema" to "不支持的题库增量内容格式",
        "Embedded quiz images exceed 5 MiB in total" to "题库内嵌图片总量超过 5 MiB",
        "Question ids must be unique" to "题目 ID 必须唯一",
        "Question grade is out of range" to "题目年级超出范围",
        "Question metadata is too large" to "题目元数据过大",
        "Question tags are invalid" to "题目标签无效",
        "Unsupported question type" to "不支持的题目类型",
        "A quiz result needs 1 to 10 questions" to "答题结果必须包含 1 到 10 道题",
        "A rewarded attempt must have every recorded answer correct" to "获得奖励的答题必须全部正确",
        "A quiz trigger needs 1 to 50 packages" to "答题触发必须包含 1 到 50 个应用",
        "Quiz trigger packages contain duplicates" to "答题触发应用包含重复项",
        "Quiz trigger packages must contain strings" to "答题触发应用必须是字符串",
        "Quiz trigger packages must also have a daily budget or schedule" to "答题触发应用还必须设置每日额度或计划",
        "An always-allowed package cannot be used as a quiz trigger target" to "始终允许的应用不能作为答题触发目标",
        "An always-blocked package cannot be used as a quiz trigger target" to "始终阻止的应用不能作为答题触发目标",
        "Each quiz trigger must be an object" to "每个答题触发必须是对象",
        "Each quiz trigger needs a packages array" to "每个答题触发都需要应用数组",
        "At most 32 quiz triggers are supported" to "最多支持 32 个答题触发",
        "At most 3 help contacts are supported" to "最多支持 3 个紧急联系人",
        "Each help contact must be an object" to "每个紧急联系人必须是对象",
        "help_contacts contains duplicates" to "紧急联系人包含重复项",
        "help contact label must be a string" to "紧急联系人名称必须是字符串",
        "help contact phone must be a string" to "紧急联系人电话必须是字符串",
        "help contact label must be 1 to 40 printable characters" to "紧急联系人名称必须为 1 到 40 个可打印字符",
        "help contact phone must be a dialable number" to "紧急联系人电话必须是可拨号码",
        "help contact phone must contain 2 to 20 digits" to "紧急联系人电话必须包含 2 到 20 位数字",
        "At most 200 daily budgets are supported" to "最多支持 200 个每日额度",
        "At most 500 blocked packages are supported" to "最多支持 500 个阻止应用",
        "At most 500 always-allowed packages are supported" to "最多支持 500 个始终允许应用",
        "At most 100 scheduled packages are supported" to "最多支持 100 个计划应用",
        "At most 2000 apps are supported" to "最多支持 2000 个应用",
        "At most 500 app usage entries are supported" to "最多支持 500 条应用使用记录",
        "blocked_packages must contain strings" to "阻止应用列表必须包含字符串",
        "blocked_packages contains duplicates" to "阻止应用列表包含重复项",
        "always_allowed_packages must contain strings" to "始终允许应用列表必须包含字符串",
        "always_allowed_packages contains duplicates" to "始终允许应用列表包含重复项",
        "apps contains duplicate packages" to "应用列表包含重复包名",
        "schedules must be an object" to "使用计划必须是对象",
        "Each schedule must be an array" to "每个使用计划必须是数组",
        "Each schedule window must be an object" to "每个使用时段必须是对象",
        "A scheduled package needs 1 to 21 windows" to "每个计划应用需要 1 到 21 个时段",
        "A window needs at least one day" to "每个时段至少需要一个星期",
        "A window needs exactly days, start_minute and duration_minutes" to "每个时段必须包含星期、开始分钟和持续分钟数",
        "Day values must be 1 (Monday) to 7 (Sunday)" to "星期值必须为 1（周一）到 7（周日）",
        "App category is out of range" to "应用分类超出范围",
        "App label must not be empty" to "应用名称不能为空",
        "quiz_difficulties may contain easy, medium, or hard" to "题库难度只能包含 easy、medium 或 hard",
        "quiz_languages must contain BCP-47 language tags" to "题库语言必须包含 BCP-47 语言标签",
        "day must be an ISO-8601 date" to "日期必须使用 ISO-8601 格式",
        "days must be an array" to "星期必须是数组",
        "days must contain integers" to "星期必须包含整数",
        "days contains duplicates" to "星期包含重复项",
        "timezone must be an IANA zone id" to "时区必须是 IANA 时区 ID",
        "detail must be at most 200 characters" to "详情最多 200 个字符",
        "health payload is too large" to "健康数据过大",
        "occurred_at is outside the accepted window" to "发生时间不在允许范围内",
        "limit must be between 1 and 100" to "数量限制必须介于 1 到 100 之间",
        "revision must not be negative" to "修订号不能为负数",
        "Device name must be 1 to 80 characters" to "设备名称必须为 1 到 80 个字符",
        "Key transition id has an invalid format" to "密钥转换 ID 格式无效",
        "Help request id has an invalid format" to "求助请求 ID 格式无效",
        "Quiz trigger id has an invalid format" to "答题触发 ID 格式无效",
        "LiveKit is not configured" to "未配置 LiveKit",
        "The media room could not be revoked; retry ending the call" to "无法撤销媒体房间；请重试结束通话",
        "Call end state could not be recorded" to "无法记录通话结束状态"
    )

    private val questionSuffixes = mapOf(
        "must be an object" to "必须是对象",
        "has an unsupported question type" to "的题目类型不受支持",
        "answer index is out of range" to "的答案序号超出范围",
        "answer_indices must be an array" to "的 answer_indices 必须是数组",
        "needs 2 or more, but not all, marked correct" to "需要至少标记两个但并非全部正确答案",
        "answer indices must be integers" to "的答案序号必须是整数",
        "answer indices are out of range or duplicated" to "的答案序号超出范围或重复",
        "answer must be true or false" to "的答案必须为 true 或 false",
        "accepted_answers must be an array" to "的 accepted_answers 必须是数组",
        "needs 1 to 8 accepted answers" to "需要 1 到 8 个可接受答案",
        "accepted answers must be strings" to "的可接受答案必须是字符串",
        "accepted answers must be distinct after normalization" to "的可接受答案规范化后不能重复",
        "grade must be between 0 and 12" to "的年级必须介于 0 到 12 之间",
        "difficulty must be easy, medium, or hard" to "的难度必须是 easy、medium 或 hard",
        "language must be a BCP-47 tag" to "的语言必须是 BCP-47 标签",
        "tags must be an array" to "的标签必须是数组",
        "supports at most 32 tags" to "最多支持 32 个标签",
        "tags must be strings" to "的标签必须是字符串",
        "options must be an array" to "的选项必须是数组",
        "needs 2 to 8 options" to "需要 2 到 8 个选项",
        "options must be strings" to "的选项必须是字符串",
        "options must be distinct" to "的选项不能重复",
        "image_base64 and image_mime must be supplied together" to "的图片数据和 MIME 类型必须同时提供",
        "image is not valid base64" to "的图片不是有效 Base64",
        "image must be 4 bytes to 512 KiB" to "的图片必须为 4 字节到 512 KiB",
        "image must be JPEG or PNG with a matching MIME type" to "的图片必须是 JPEG 或 PNG，且 MIME 类型匹配"
    )

    private val questionFields = mapOf(
        "id" to "ID",
        "prompt" to "题目",
        "subject" to "科目",
        "tag" to "标签",
        "option" to "选项",
        "accepted answer" to "可接受答案"
    )

    private fun localizeQuestionMessage(message: String): String? {
        val match = Regex("""^Question (\d+) (.+)$""").matchEntire(message) ?: return null
        val number = match.groupValues[1]
        val suffix = match.groupValues[2]
        questionSuffixes[suffix]?.let { return "题目 $number $it" }
        Regex("""^has an unknown field: (.+)$""").matchEntire(suffix)?.let {
            return "题目 $number 包含未知字段：${it.groupValues[1]}"
        }
        Regex("""^(id|prompt|subject|tag|option|accepted answer) must be 1 to (\d+) characters$""")
            .matchEntire(suffix)?.let {
                val fieldName = questionFields.getValue(it.groupValues[1])
                return "题目 $number 的$fieldName 必须为 1 到 ${it.groupValues[2]} 个字符"
            }
        Regex("""^(id|prompt|subject|tag|option|accepted answer) is invalid or too long$""")
            .matchEntire(suffix)?.let {
                val fieldName = questionFields.getValue(it.groupValues[1])
                return "题目 $number 的$fieldName 无效或过长"
            }
        return null
    }

    private fun localizeQuizQuantityMessage(message: String): String? {
        Regex("""^Quiz pack needs 1 to (\d+) questions$""").matchEntire(message)?.let {
            return "题库必须包含 1 到 ${it.groupValues[1]} 道题"
        }
        Regex("""^Quiz delta needs 1 to (\d+) operations$""").matchEntire(message)?.let {
            return "题库增量需要 1 到 ${it.groupValues[1]} 个操作"
        }
        Regex("""^Quiz delta result needs 1 to (\d+) questions$""").matchEntire(message)?.let {
            return "题库增量结果必须包含 1 到 ${it.groupValues[1]} 道题"
        }
        return null
    }

    private val jsonLabels = mapOf(
        "Quiz envelope" to "题库信封",
        "Quiz payload" to "题库内容",
        "Quiz delta envelope" to "题库增量信封",
        "Quiz delta payload" to "题库增量内容",
        "Quiz base envelope" to "题库基础信封",
        "Quiz target envelope" to "题库目标信封"
    )

    private fun localizeJsonLabelMessage(message: String): String? {
        Regex("""^(.+) must be a JSON object$""").matchEntire(message)?.let {
            jsonLabels[it.groupValues[1]]?.let { label -> return "$label 必须是 JSON 对象" }
        }
        Regex("""^(.+) is not valid JSON$""").matchEntire(message)?.let {
            jsonLabels[it.groupValues[1]]?.let { label -> return "$label 不是有效 JSON" }
        }
        return null
    }

    private fun field(name: String): String = when (name) {
        "timezone" -> "时区"
        "schema" -> "格式版本"
        "days" -> "星期"
        "day" -> "日期"
        "minutes" -> "分钟数"
        "detail" -> "详情"
        "revision" -> "修订号"
        "limit" -> "数量限制"
        "event" -> "事件"
        "reason" -> "原因"
        "nonce" -> "随机数"
        "attempt_id" -> "答题 ID"
        "question_id" -> "题目 ID"
        "trigger_id" -> "触发 ID"
        "call_id" -> "通话 ID"
        "device_id" -> "设备 ID"
        "client_request_id" -> "客户端请求 ID"
        "base_version" -> "基础版本"
        "expires_in_minutes" -> "有效分钟数"
        "start_minute" -> "开始分钟"
        "duration_minutes" -> "持续分钟数"
        "help_contacts" -> "紧急联系人"
        "blocked_packages" -> "阻止应用列表"
        "always_allowed_packages" -> "始终允许应用列表"
        "apps" -> "应用列表"
        "quiz_difficulties" -> "题库难度"
        "quiz_languages" -> "题库语言"
        "schedules" -> "使用计划"
        "quiz_triggers" -> "答题触发规则"
        "quiz_subjects" -> "题库科目"
        "quiz_grades" -> "题库年级"
        "quiz_tags" -> "题库标签"
        "packages" -> "应用包"
        "type" -> "类型"
        "id" -> "ID"
        "enabled" -> "启用状态"
        "reward_minutes" -> "奖励分钟数"
        "max_attempts_per_day" -> "每日尝试次数"
        "cooldown_seconds" -> "冷却秒数"
        "budget_warning_minutes" -> "额度提醒分钟数"
        "continuous_use_reminder_minutes" -> "连续使用提醒分钟数"
        "quiz_question_count" -> "每次答题题数"
        "expires_at" -> "到期时间"
        "occurred_at" -> "发生时间"
        else -> name
    }

    fun localize(message: String): String {
        exact[message]?.let { return it }
        val named = Regex("""^([a-z_]+) (.+)$""").matchEntire(message)
        if (named != null) {
            val name = field(named.groupValues[1])
            val suffix = named.groupValues[2]
            when (suffix) {
                "must be a string" -> return "$name 必须是字符串"
                "must be a boolean" -> return "$name 必须是布尔值"
                "must be an integer" -> return "$name 必须是整数"
                "must be an object" -> return "$name 必须是对象"
                "must be an array" -> return "$name 必须是数组"
                "contains duplicates" -> return "$name 包含重复项"
                "must contain strings" -> return "$name 必须包含字符串"
                "must contain integers" -> return "$name 必须包含整数"
                "has an invalid format" -> return "$name 格式无效"
                "must not be negative" -> return "$name 不能为负数"
                "is too long" -> return "$name 过长"
                "must be 1 to 100" -> return "$name 必须介于 1 到 100 之间"
                "must be between 1 and 100" -> return "$name 必须介于 1 到 100 之间"
                "must be between 1 and 1440" -> return "$name 必须介于 1 到 1440 之间"
                "must be between 15 and 1440 minutes" -> return "$name 必须介于 15 到 1440 分钟之间"
                "must be between 1 and 10080" -> return "$name 必须介于 1 到 10080 之间"
                "must be between 1 and 60" -> return "$name 必须介于 1 到 60 之间"
                "needs at least one day" -> return "$name 至少需要一个星期"
                "contains too many days" -> return "$name 包含过多星期"
                "contains too many values" -> return "$name 包含过多值"
                "contains an invalid value" -> return "$name 包含无效值"
                "has an invalid length" -> return "$name 长度无效"
                "values must be between 1 and 7" -> return "$name 的值必须介于 1 到 7 之间"
            }
        }
        val between = Regex("""^([a-z_]+) must be between (.+) and (.+)$""").matchEntire(message)
        if (between != null) {
            return "${field(between.groupValues[1])} 必须介于 ${between.groupValues[2]} 到 ${between.groupValues[3]} 之间"
        }
        val valuesBetween = Regex("""^([a-z_]+) values must be between (.+) and (.+)$""").matchEntire(message)
        if (valuesBetween != null) {
            return "${field(valuesBetween.groupValues[1])} 的值必须介于 ${valuesBetween.groupValues[2]} 到 ${valuesBetween.groupValues[3]} 之间"
        }
        localizeQuestionMessage(message)?.let { return it }
        localizeQuizQuantityMessage(message)?.let { return it }
        localizeJsonLabelMessage(message)?.let { return it }
        Regex("""^Unknown policy fields: (.+)$""").matchEntire(message)?.let {
            return "未知策略字段：${it.groupValues[1]}"
        }
        Regex("""^Unknown quiz trigger fields: (.+)$""").matchEntire(message)?.let {
            return "未知答题触发字段：${it.groupValues[1]}"
        }
        Regex("""^Unknown help contact fields: (.+)$""").matchEntire(message)?.let {
            return "未知紧急联系人字段：${it.groupValues[1]}"
        }
        Regex("""^Unsupported quiz trigger type: (.+)$""").matchEntire(message)?.let {
            return "不支持的答题触发类型：${it.groupValues[1]}"
        }
        Regex("""^Quiz pack version (.+) must be newer than active version (.+)$""").matchEntire(message)?.let {
            return "题库版本 ${it.groupValues[1]} 必须高于当前启用版本 ${it.groupValues[2]}"
        }
        Regex("""^Invalid Android package name: (.+)$""").matchEntire(message)?.let {
            return "无效的 Android 包名：${it.groupValues[1]}"
        }
        Regex("""^Duplicate question id: (.+)$""").matchEntire(message)?.let {
            return "重复的题目 ID：${it.groupValues[1]}"
        }
        return message
    }
}
