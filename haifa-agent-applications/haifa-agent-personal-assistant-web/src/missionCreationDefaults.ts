export type MissionMode = "STANDARD" | "DEEP_RESEARCH";

export interface ResearchDefaults {
  scope: string;
  timeRange: string;
  region: string;
  audience: string;
  sourcePreferences: string[];
  exclusions: string[];
  deliveryFormat: string;
}

export function defaultMissionAcceptanceCriteria(mode: MissionMode): string[] {
  return mode === "DEEP_RESEARCH"
    ? [
        "覆盖目标涉及的关键事实、发展过程与当前状态",
        "关键结论提供可追溯来源，并说明不确定性与证据限制",
        "形成结构化完整报告，包含来源清单与待核实问题",
      ]
    : [
        "交付结果覆盖目标要求的核心范围",
        "关键结论说明依据、限制与未完成项",
        "形成清晰、可继续使用的最终交付",
      ];
}

export function defaultResearchBrief(objective: string): ResearchDefaults {
  const explicitYearRange = objective.trim().match(/(?:过去|近)\s*(\d{1,2})\s*年/);
  return {
    scope: "围绕目标开展事实调查与综合分析",
    timeRange: explicitYearRange ? `过去${explicitYearRange[1]}年至今` : "未指定（规划时确认）",
    region: "未指定（以目标明确地区为准）",
    audience: "任务发起人",
    sourcePreferences: ["一手与官方来源", "权威数据库与专业资料", "独立可靠来源"],
    exclusions: ["无法追溯原始出处的转载", "缺少事实依据的纯营销材料"],
    deliveryFormat: "结构化 Markdown 完整报告，包含来源与引用",
  };
}
