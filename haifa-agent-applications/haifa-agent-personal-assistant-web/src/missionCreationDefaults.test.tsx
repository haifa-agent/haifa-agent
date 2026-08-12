import { describe, expect, it } from "vitest";
import { defaultMissionAcceptanceCriteria, defaultResearchBrief } from "./missionCreationDefaults";

describe("Mission creation defaults", () => {
  it("uses mode-specific acceptance criteria", () => {
    expect(defaultMissionAcceptanceCriteria("STANDARD")).toHaveLength(3);
    expect(defaultMissionAcceptanceCriteria("DEEP_RESEARCH")).toContain(
      "关键结论提供可追溯来源，并说明不确定性与证据限制",
    );
  });

  it("keeps research defaults domain-neutral while preserving an explicit year range", () => {
    const defaults = defaultResearchBrief("梳理景宁小水电近 3 年的发展，并与 Paris 的案例比较");

    expect(defaults.scope).toBe("围绕目标开展事实调查与综合分析");
    expect(defaults.timeRange).toBe("过去3年至今");
    expect(defaults.region).toBe("未指定（以目标明确地区为准）");
    expect(defaults.sourcePreferences).toEqual(["一手与官方来源", "权威数据库与专业资料", "独立可靠来源"]);
    expect(JSON.stringify(defaults)).not.toContain("景宁县小水电发展与投资");
    expect(JSON.stringify(defaults)).not.toContain("浙江省丽水市景宁畲族自治县");
  });

  it("does not invent a time range when the goal does not provide one", () => {
    expect(defaultResearchBrief("分析一个新市场").timeRange).toBe("未指定（规划时确认）");
  });
});
