export interface PolicyFormState {
  timezone: string;
  enabled: boolean;
  launcherEnabled: boolean;
  quizGateEnabled: boolean;
  rewardMinutes: number;
  maxAttemptsPerDay: number;
  cooldownSeconds: number;
  budgetWarningMinutes: number;
  continuousUseReminderMinutes: number;
  quizQuestionCount: number;
  quizSubjects: string;
  quizGrades: string;
  quizDifficulties: string;
  quizLanguages: string;
  quizTags: string;
  quizTriggers: QuizTriggerForm[];
  helpContacts: HelpContactForm[];
  apps: AppPolicy[];
  schedulesText: string;
}

export type QuizTriggerType = "total_usage" | "app_enter" | "continuous_use" | "schedule_start";

export interface QuizTriggerForm {
  type: QuizTriggerType;
  packages: string;
  minutes: number;
  delaySeconds: number;
  days: string;
  startMinute: number;
}

export interface HelpContactForm {
  label: string;
  phone: string;
}

export interface AppPolicy {
  packageName: string;
  label: string;
  category: number;
  dailyMinutes: number | null;
  blocked: boolean;
  alwaysAllowed: boolean;
}

export const CATEGORY_LABELS: Record<number, string> = {
  [-1]: "Uncategorized",
  0: "Games",
  1: "Audio",
  2: "Video",
  3: "Images",
  4: "Social",
  5: "News",
  6: "Maps",
  7: "Productivity",
  8: "Accessibility"
};

export const emptyPolicy: PolicyFormState = {
  timezone: "Asia/Shanghai",
  enabled: true,
  launcherEnabled: false,
  quizGateEnabled: true,
  rewardMinutes: 10,
  maxAttemptsPerDay: 8,
  cooldownSeconds: 60,
  budgetWarningMinutes: 15,
  continuousUseReminderMinutes: 30,
  quizQuestionCount: 1,
  quizSubjects: "",
  quizGrades: "",
  quizDifficulties: "",
  quizLanguages: "",
  quizTags: "",
  quizTriggers: [],
  helpContacts: [],
  apps: [
    { packageName: "com.example.game", label: "Example game", category: 0, dailyMinutes: 60, blocked: false, alwaysAllowed: false },
    { packageName: "com.example.blocked", label: "Always blocked", category: -1, dailyMinutes: null, blocked: true, alwaysAllowed: false }
  ],
  schedulesText: "{\n  \"com.example.game\": [\n    { \"days\": [1, 2, 3, 4, 5], \"start_minute\": 960, \"duration_minutes\": 120 }\n  ]\n}"
};


export interface ScheduleRuleForm {
  packageName: string;
  days: string;
  startMinute: number;
  durationMinutes: number;
}

export function scheduleRulesFromText(text: string): ScheduleRuleForm[] {
  const parsed: unknown = JSON.parse(text);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error("Schedules must be a JSON object");
  }
  const rules: ScheduleRuleForm[] = [];
  for (const [packageName, windows] of Object.entries(parsed as Record<string, unknown>)) {
    if (!Array.isArray(windows)) throw new Error(`Schedule for ${packageName} must be a list`);
    for (const window of windows) {
      const item = window as Record<string, unknown>;
      const days = Array.isArray(item.days) ? item.days.map(Number) : [];
      rules.push({
        packageName,
        days: days.join(", "),
        startMinute: Number(item.start_minute ?? 0),
        durationMinutes: Number(item.duration_minutes ?? 60)
      });
    }
  }
  return rules;
}

export function scheduleRulesToText(rules: ScheduleRuleForm[]): string {
  const grouped: Record<string, Array<{ days: number[]; start_minute: number; duration_minutes: number }>> = {};
  for (const rule of rules) {
    const packageName = rule.packageName.trim();
    if (!packageName) continue;
    const days = rule.days.split(",").map((entry) => entry.trim()).filter(Boolean).map(Number)
      .filter((day) => Number.isInteger(day) && day >= 1 && day <= 7);
    if (!Number.isInteger(rule.startMinute) || rule.startMinute < 0 || rule.startMinute > 1439) {
      throw new Error(`Schedule start for ${packageName} must be between 0 and 1439`);
    }
    if (!Number.isInteger(rule.durationMinutes) || rule.durationMinutes < 1 || rule.durationMinutes > 1440) {
      throw new Error(`Schedule duration for ${packageName} must be between 1 and 1440 minutes`);
    }
    (grouped[packageName] ??= []).push({
      days: [...new Set(days)].sort((left, right) => left - right),
      start_minute: rule.startMinute,
      duration_minutes: rule.durationMinutes
    });
  }
  return JSON.stringify(grouped, null, 2);
}

export function formFromPolicyPayload(payload: string): PolicyFormState {
  const parsed = JSON.parse(payload) as Record<string, unknown>;
  const budgets = (parsed.daily_budgets ?? {}) as Record<string, number>;
  const blocked = new Set(Array.isArray(parsed.blocked_packages)
    ? parsed.blocked_packages.map(String)
    : []);
  const alwaysAllowed = new Set(Array.isArray(parsed.always_allowed_packages)
    ? parsed.always_allowed_packages.map(String)
    : []);
  const schedules = (parsed.schedules ?? {}) as Record<string, unknown>;
  const names = new Set([
    ...Object.keys(budgets), ...blocked, ...alwaysAllowed, ...Object.keys(schedules)
  ]);
  const apps = [...names].sort().map((packageName) => ({
    packageName,
    label: packageName,
    category: 0,
    dailyMinutes: typeof budgets[packageName] === "number" ? budgets[packageName] : null,
    blocked: blocked.has(packageName),
    alwaysAllowed: alwaysAllowed.has(packageName)
  }));
  return {
    timezone: String(parsed.timezone ?? "Asia/Shanghai"),
    enabled: Boolean(parsed.enabled),
    launcherEnabled: Boolean(parsed.launcher_enabled),
    quizGateEnabled: Boolean(parsed.quiz_gate_enabled),
    rewardMinutes: Number(parsed.reward_minutes ?? 10),
    maxAttemptsPerDay: Number(parsed.max_attempts_per_day ?? 8),
    cooldownSeconds: Number(parsed.cooldown_seconds ?? 60),
    budgetWarningMinutes: Number(parsed.budget_warning_minutes ?? 0),
    continuousUseReminderMinutes: Number(parsed.continuous_use_reminder_minutes ?? 0),
    quizQuestionCount: Number(parsed.quiz_question_count ?? 1),
    quizSubjects: joinFilters(parsed.quiz_subjects),
    quizGrades: joinFilters(parsed.quiz_grades),
    quizDifficulties: joinFilters(parsed.quiz_difficulties),
    quizLanguages: joinFilters(parsed.quiz_languages),
    quizTags: joinFilters(parsed.quiz_tags),
    quizTriggers: readTriggers(parsed.quiz_triggers),
    helpContacts: readHelpContacts(parsed.help_contacts),
    apps,
    schedulesText: JSON.stringify(schedules, null, 2)
  };
}

export function policyFromForm(form: PolicyFormState): Record<string, unknown> {
  const budgets: Record<string, number> = {};
  const blocked: string[] = [];
  const alwaysAllowed: string[] = [];
  const seen = new Set<string>();
  for (const app of form.apps) {
    const name = app.packageName.trim();
    if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(name)) {
      throw new Error(`Invalid package name: ${name}`);
    }
    if (seen.has(name)) throw new Error(`Package ${name} appears more than once`);
    seen.add(name);
    if (app.blocked && app.alwaysAllowed) {
      throw new Error(`${name} cannot be both always blocked and always allowed`);
    }
    if (app.blocked) {
      blocked.push(name);
      continue;
    }
    if (app.alwaysAllowed) alwaysAllowed.push(name);
    if (app.dailyMinutes !== null) {
      if (!Number.isInteger(app.dailyMinutes) || app.dailyMinutes < 0 || app.dailyMinutes > 1440) {
        throw new Error(`Budget for ${name} must be an integer between 0 and 1440`);
      }
      budgets[name] = app.dailyMinutes;
    }
  }
  const schedules = JSON.parse(form.schedulesText) as Record<string, unknown>;
  if (!schedules || Array.isArray(schedules) || typeof schedules !== "object") {
    throw new Error("Schedules must be a JSON object");
  }
  if (!Number.isInteger(form.rewardMinutes) || form.rewardMinutes < 0 || form.rewardMinutes > 1440) {
    throw new Error("Reward minutes must be between 0 and 1440");
  }
  if (!Number.isInteger(form.maxAttemptsPerDay) || form.maxAttemptsPerDay < 0 || form.maxAttemptsPerDay > 1000) {
    throw new Error("Daily attempts must be between 0 and 1000");
  }
  if (!Number.isInteger(form.cooldownSeconds) || form.cooldownSeconds < 0 || form.cooldownSeconds > 86400) {
    throw new Error("Cooldown must be between 0 and 86400 seconds");
  }
  if (!Number.isInteger(form.budgetWarningMinutes) || form.budgetWarningMinutes < 0
      || form.budgetWarningMinutes > 1440) {
    throw new Error("Budget warning must be between 0 and 1440 minutes");
  }
  if (!Number.isInteger(form.continuousUseReminderMinutes)
      || form.continuousUseReminderMinutes < 0 || form.continuousUseReminderMinutes > 1440) {
    throw new Error("Continuous-use reminder must be between 0 and 1440 minutes");
  }
  if (!Number.isInteger(form.quizQuestionCount) || form.quizQuestionCount < 1 || form.quizQuestionCount > 10) {
    throw new Error("Questions per attempt must be between 1 and 10");
  }
  const quizSubjects = parseTextFilters(form.quizSubjects, 64, "subject");
  const quizGrades = parseIntegerFilters(form.quizGrades, 0, 12, "grade");
  const quizDifficulties = parseTextFilters(form.quizDifficulties, 16, "difficulty");
  for (const difficulty of quizDifficulties) {
    if (!["easy", "medium", "hard"].includes(difficulty)) {
      throw new Error("Difficulty filters may only contain easy, medium, or hard");
    }
  }
  const quizLanguages = parseTextFilters(form.quizLanguages, 32, "language");
  for (const language of quizLanguages) {
    if (!/^[a-z]{2,8}(-[a-z0-9]{1,8})*$/.test(language)) {
      throw new Error(`Invalid language tag: ${language}`);
    }
  }
  const quizTags = parseTextFilters(form.quizTags, 48, "tag");
  if (form.quizTriggers.length > 32) throw new Error("At most 32 quiz triggers are supported");
  const triggerKeys = new Set<string>();
  const quizTriggers = form.quizTriggers.map((trigger, index) => {
    const packages = parsePackageList(trigger.packages, `trigger ${index + 1}`);
    for (const packageName of packages) {
      if (blocked.includes(packageName)) {
        throw new Error(`${packageName} is always blocked and cannot be a quiz trigger`);
      }
      if (alwaysAllowed.includes(packageName)) {
        throw new Error(`${packageName} is always allowed and cannot be a quiz trigger`);
      }
      if (!(packageName in budgets) && !(packageName in schedules)) {
        throw new Error(`${packageName} needs a daily budget or schedule before it can trigger a quiz`);
      }
    }
    const normalized: Record<string, unknown> = { type: trigger.type, packages };
    if (trigger.type === "total_usage" || trigger.type === "continuous_use") {
      if (!Number.isInteger(trigger.minutes) || trigger.minutes < 1 || trigger.minutes > 1440) {
        throw new Error("Usage trigger minutes must be between 1 and 1440");
      }
      normalized.minutes = trigger.minutes;
    } else if (trigger.type === "app_enter") {
      if (!Number.isInteger(trigger.delaySeconds) || trigger.delaySeconds < 0
          || trigger.delaySeconds > 3600) {
        throw new Error("App-enter delay must be between 0 and 3600 seconds");
      }
      normalized.delay_seconds = trigger.delaySeconds;
    } else if (trigger.type === "schedule_start") {
      const days = parseDays(trigger.days);
      if (!Number.isInteger(trigger.startMinute) || trigger.startMinute < 0
          || trigger.startMinute > 1439) {
        throw new Error("Schedule trigger start must be between 0 and 1439 minutes");
      }
      normalized.days = days;
      normalized.start_minute = trigger.startMinute;
    } else {
      throw new Error(`Unsupported quiz trigger type: ${String(trigger.type)}`);
    }
    const key = JSON.stringify(normalized);
    if (triggerKeys.has(key)) throw new Error("Duplicate quiz trigger configuration");
    triggerKeys.add(key);
    return normalized;
  });
  if (!form.timezone.trim()) throw new Error("Timezone is required");
  const helpContacts = normalizeHelpContacts(form.helpContacts);
  return {
    schema: 1,
    revision: 0,
    timezone: form.timezone.trim(),
    enabled: form.enabled,
    launcher_enabled: form.launcherEnabled,
    quiz_gate_enabled: form.quizGateEnabled,
    reward_minutes: form.rewardMinutes,
    max_attempts_per_day: form.maxAttemptsPerDay,
    cooldown_seconds: form.cooldownSeconds,
    budget_warning_minutes: form.budgetWarningMinutes,
    continuous_use_reminder_minutes: form.continuousUseReminderMinutes,
    quiz_question_count: form.quizQuestionCount,
    quiz_subjects: quizSubjects,
    quiz_grades: quizGrades,
    quiz_difficulties: quizDifficulties,
    quiz_languages: quizLanguages,
    quiz_tags: quizTags,
    quiz_triggers: quizTriggers,
    help_contacts: helpContacts,
    daily_budgets: budgets,
    blocked_packages: blocked,
    always_allowed_packages: alwaysAllowed,
    schedules
  };
}

function readHelpContacts(value: unknown): HelpContactForm[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object")
    .map((item) => ({
      label: String(item.label ?? ""),
      phone: String(item.phone ?? "")
    }));
}

function normalizeHelpContacts(contacts: HelpContactForm[]): Array<{ label: string; phone: string }> {
  const rows = (Array.isArray(contacts) ? contacts : [])
    .map((contact) => ({
      label: String(contact.label ?? "").trim(),
      phone: String(contact.phone ?? "").trim()
    }))
    .filter((contact) => contact.label !== "" || contact.phone !== "");
  if (rows.length > 3) throw new Error("At most 3 emergency contacts are supported");
  const seen = new Set<string>();
  return rows.map((contact, index) => {
    if (contact.label.length < 1 || contact.label.length > 40
        || /[\u0000-\u001f\u007f]/.test(contact.label)) {
      throw new Error(`Emergency contact ${index + 1} needs a label of 1 to 40 printable characters`);
    }
    if (contact.phone.length > 24 || !/^\+?[0-9][0-9 ()-]{0,23}$/.test(contact.phone)) {
      throw new Error(`Emergency contact ${index + 1} needs a dialable phone number`);
    }
    const digits = (contact.phone.match(/[0-9]/g) ?? []).length;
    if (digits < 2 || digits > 20) {
      throw new Error(`Emergency contact ${index + 1} must contain 2 to 20 digits`);
    }
    const key = `${contact.label}\u0000${contact.phone}`;
    if (seen.has(key)) throw new Error("Emergency contacts must be unique");
    seen.add(key);
    return contact;
  });
}

function joinFilters(value: unknown): string {
  return Array.isArray(value) ? value.map(String).join(", ") : "";
}

function readTriggers(value: unknown): QuizTriggerForm[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object")
    .map((item) => {
      const type = item.type === "app_enter" || item.type === "continuous_use"
        || item.type === "schedule_start" ? item.type : "total_usage";
      return {
        type,
        packages: Array.isArray(item.packages) ? item.packages.map(String).join(", ") : "",
        minutes: Number(item.minutes ?? 30),
        delaySeconds: Number(item.delay_seconds ?? 0),
        days: Array.isArray(item.days) ? item.days.map(String).join(", ") : "1, 2, 3, 4, 5",
        startMinute: Number(item.start_minute ?? 960)
      };
    });
}

function parsePackageList(value: string, label: string): string[] {
  const packages = value.split(",").map((entry) => entry.trim()).filter(Boolean);
  if (packages.length < 1 || packages.length > 50) {
    throw new Error(`A quiz trigger needs 1 to 50 packages (${label})`);
  }
  for (const packageName of packages) {
    if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(packageName)) {
      throw new Error(`Invalid package in ${label}: ${packageName}`);
    }
  }
  if (new Set(packages).size !== packages.length) throw new Error(`Duplicate package in ${label}`);
  return packages.sort();
}

function parseDays(value: string): number[] {
  const days = value.split(",").map((entry) => entry.trim()).filter(Boolean).map((entry) => {
    if (!/^[1-7]$/.test(entry)) throw new Error("Trigger days must be integers from 1 to 7");
    return Number(entry);
  });
  if (days.length < 1 || days.length > 7) throw new Error("A schedule trigger needs 1 to 7 days");
  if (new Set(days).size !== days.length) throw new Error("Duplicate trigger day");
  return days.sort((left, right) => left - right);
}

function parseTextFilters(value: string, maxLength: number, label: string): string[] {
  const values = value.split(",").map((entry) => entry.trim().toLowerCase()).filter(Boolean);
  for (const entry of values) {
    if (entry.length > maxLength) throw new Error(`${label} filter is too long: ${entry}`);
  }
  if (new Set(values).size !== values.length) throw new Error(`Duplicate ${label} filter`);
  return values.sort();
}

function parseIntegerFilters(value: string, min: number, max: number, label: string): number[] {
  const values = value.split(",").map((entry) => entry.trim()).filter(Boolean).map((entry) => {
    if (!/^[0-9]+$/.test(entry)) throw new Error(`${label} filters must be integers`);
    const parsed = Number(entry);
    if (parsed < min || parsed > max) throw new Error(`${label} filters must be between ${min} and ${max}`);
    return parsed;
  });
  if (new Set(values).size !== values.length) throw new Error(`Duplicate ${label} filter`);
  return values.sort((left, right) => left - right);
}
