import {
  Boxes,
  CheckCircle2,
  Database,
  Sparkles,
  Wrench,
} from "lucide-react";
import type { AdminCapability } from "./types";

function icon(kind: AdminCapability["kind"]) {
  if (kind === "MCP") return Database;
  if (kind === "SKILL") return Sparkles;
  return Wrench;
}

export function AdminCapabilityDetail({
  capability,
}: {
  capability: AdminCapability;
}) {
  const Icon = icon(capability.kind);
  return (
    <section className="admin-capability-detail" aria-label="注册能力详情">
      <div className="admin-capability-title">
        <span className="admin-capability-icon"><Icon size={19} /></span>
        <div>
          <small>{capability.kind} REGISTRATION</small>
          <h1>{capability.name}</h1>
          <p>{capability.displayName}</p>
        </div>
        <span className="admin-capability-ready">
          <CheckCircle2 size={14} />
          {capability.status}
        </span>
      </div>

      <p className="admin-capability-description">{capability.description}</p>

      <dl className="admin-capability-attributes">
        {capability.attributes.map((attribute) => (
          <div key={attribute.label}>
            <dt>{attribute.label}</dt>
            <dd>
              <span className={`admin-status ${attribute.tone}`}>
                {attribute.value}
              </span>
            </dd>
          </div>
        ))}
      </dl>

      <div className="admin-capability-source">
        <Boxes size={15} />
        <div>
          <small>来源 / Provider</small>
          <code>{capability.source}</code>
        </div>
      </div>

      {capability.tags.length > 0 && (
        <div className="admin-capability-tags">
          {capability.tags.map((tag) => <span key={tag}>{tag}</span>)}
        </div>
      )}

      <div className="admin-raw admin-capability-raw">
        <small>完整注册快照</small>
        <pre>{JSON.stringify(capability.details, null, 2)}</pre>
      </div>
    </section>
  );
}
