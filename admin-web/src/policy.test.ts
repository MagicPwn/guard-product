import { describe, expect, it } from "vitest";
import {
  emptyPolicy,
  formFromPolicyPayload,
  policyFromForm,
  type PolicyFormState
} from "./policy";

function draft(): PolicyFormState {
  return JSON.parse(JSON.stringify(emptyPolicy)) as PolicyFormState;
}

describe("policy form conversion", () => {
  it("round-trips budgets, schedules, filters, triggers, and emergency contacts", () => {
    const form = draft();
    form.timezone = "Asia/Shanghai";
    form.quizGrades = "5, 3";
    form.quizSubjects = "Math, Science";
    form.quizTriggers = [{
      type: "total_usage",
      packages: "com.example.game",
      minutes: 30,
      delaySeconds: 0,
      days: "1, 2, 3",
      startMinute: 960
    }];
    form.helpContacts = [{ label: "Mum", phone: "+86 138-0000-0000" }];
    form.schedulesText = JSON.stringify({
      "com.example.game": [{ days: [1, 2, 3, 4, 5], start_minute: 960, duration_minutes: 120 }]
    });

    const payload = policyFromForm(form);
    expect(payload.daily_budgets).toEqual({ "com.example.game": 60 });
    expect(payload.blocked_packages).toEqual(["com.example.blocked"]);
    expect(payload.quiz_grades).toEqual([3, 5]);
    expect(payload.quiz_subjects).toEqual(["math", "science"]);
    expect(payload.quiz_triggers).toEqual([{
      type: "total_usage",
      packages: ["com.example.game"],
      minutes: 30
    }]);

    const restored = formFromPolicyPayload(JSON.stringify(payload));
    expect(restored.timezone).toBe("Asia/Shanghai");
    expect(restored.quizGrades).toBe("3, 5");
    expect(restored.quizSubjects).toBe("math, science");
    expect(restored.quizTriggers).toEqual([{
      type: "total_usage",
      packages: "com.example.game",
      minutes: 30,
      delaySeconds: 0,
      days: "1, 2, 3, 4, 5",
      startMinute: 960
    }]);
    expect(restored.helpContacts).toEqual([{ label: "Mum", phone: "+86 138-0000-0000" }]);
    expect(restored.apps.find((app) => app.packageName === "com.example.game")?.dailyMinutes).toBe(60);
    expect(restored.apps.find((app) => app.packageName === "com.example.blocked")?.blocked).toBe(true);
  });

  it("rejects invalid package names, duplicates, and conflicting rules", () => {
    const invalid = draft();
    invalid.apps[0].packageName = "invalid";
    expect(() => policyFromForm(invalid)).toThrow(/Invalid package name/);

    const duplicate = draft();
    duplicate.apps.push({ ...duplicate.apps[0] });
    expect(() => policyFromForm(duplicate)).toThrow(/appears more than once/);

    const conflicting = draft();
    conflicting.apps[0].blocked = true;
    conflicting.apps[0].alwaysAllowed = true;
    expect(() => policyFromForm(conflicting)).toThrow(/cannot be both/);
  });

  it("rejects out-of-range numeric settings and broken schedules", () => {
    const budget = draft();
    budget.apps[0].dailyMinutes = 1441;
    expect(() => policyFromForm(budget)).toThrow(/Budget/);

    const reward = draft();
    reward.rewardMinutes = -1;
    expect(() => policyFromForm(reward)).toThrow(/Reward minutes/);

    const cooldown = draft();
    cooldown.cooldownSeconds = 1.5;
    expect(() => policyFromForm(cooldown)).toThrow(/Cooldown/);

    const schedules = draft();
    schedules.schedulesText = "[]";
    expect(() => policyFromForm(schedules)).toThrow(/Schedules/);
  });

  it("requires trigger targets to have a budget or schedule", () => {
    const form = draft();
    form.apps = form.apps.filter((app) => app.packageName !== "com.example.game");
    form.schedulesText = "{}";
    form.quizTriggers = [{
      type: "app_enter",
      packages: "com.example.game",
      minutes: 30,
      delaySeconds: 5,
      days: "",
      startMinute: 0
    }];
    expect(() => policyFromForm(form)).toThrow(/needs a daily budget or schedule/);
  });

  it("validates emergency contacts", () => {
    const form = draft();
    form.helpContacts = [{ label: "Mum", phone: "not-a-phone" }];
    expect(() => policyFromForm(form)).toThrow(/dialable phone number/);
  });
});
