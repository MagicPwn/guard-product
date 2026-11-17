import { describe, expect, it } from "vitest";
import { translateDynamic, translateEnglishToChinese } from "./i18n";

describe("Chinese operator messages", () => {
  it("translates fixed messages", () => {
    expect(translateEnglishToChinese("Authentication required")).toBe("需要登录");
    expect(translateEnglishToChinese("Device not found")).toBe("未找到设备");
  });

  it("translates dynamic policy validation errors", () => {
    expect(translateEnglishToChinese("Budget for com.example.app must be an integer between 0 and 1440"))
      .toBe("com.example.app 的每日额度必须是 0 到 1440 之间的整数");
    expect(translateEnglishToChinese("grade filters must be between 0 and 12"))
      .toBe("年级筛选条件必须在 0 到 12 之间");
    expect(translateEnglishToChinese("A quiz trigger needs 1 to 50 packages (trigger 2)"))
      .toBe("答题触发需要 1 到 50 个应用包（触发规则 2）");
  });

  it("translates dynamic quiz validation errors", () => {
    expect(translateEnglishToChinese("Question 3 id must be 1 to 128 characters"))
      .toBe("第 3 题的ID必须为 1 到 128 个字符");
    expect(translateEnglishToChinese("Question 4 options must be distinct"))
      .toBe("第 4 题的选项不能重复");
    expect(translateEnglishToChinese("Question 5 has an unsupported type"))
      .toBe("第 5 题的类型不受支持");
  });

  it("leaves unknown text untouched", () => {
    expect(translateDynamic("A brand-new untranslated message")).toBe("A brand-new untranslated message");
  });
});
