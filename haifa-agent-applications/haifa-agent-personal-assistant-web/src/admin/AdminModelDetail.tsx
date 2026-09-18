import { Bot, CalendarCheck, CircleAlert, Gauge, ShieldCheck } from "lucide-react";
import type { AdminModelBinding } from "./types";

export function AdminModelDetail({ model }: { model: AdminModelBinding }) {
  const verified = model.validationStatus === "VERIFIED";
  return (
    <article className="admin-capability-detail">
      <header className="admin-capability-title">
        <span className="admin-capability-icon"><Bot size={22} /></span>
        <div>
          <small>{model.providerDisplayName} · {model.apiStyleDisplayName}</small>
          <h1>{model.modelDisplayName}</h1>
          <p>{model.id}</p>
        </div>
        <span className={`admin-capability-ready ${verified ? "succeeded" : "failed"}`}>
          {verified ? <ShieldCheck size={15} /> : <CircleAlert size={15} />}
          {model.validationStatus}
        </span>
      </header>

      <dl className="admin-capability-attributes">
        <div><dt>Binding profile</dt><dd>{model.profileVersion}</dd></div>
        <div><dt>Preference schema</dt><dd>{model.preferenceSchemaVersion}</dd></div>
        <div><dt>Last verified</dt><dd><CalendarCheck size={13} /> {model.lastVerifiedOn}</dd></div>
        <div><dt>Availability</dt><dd>{model.availability}</dd></div>
        <div><dt>Context window</dt><dd><Gauge size={13} /> {model.contextWindow.toLocaleString()}</dd></div>
        <div><dt>Maximum output</dt><dd>{model.maxOutputTokens.toLocaleString()}</dd></div>
      </dl>

      {model.safeErrorCode && (
        <div className="admin-error"><CircleAlert size={17} />{model.safeErrorCode}</div>
      )}

      <section className="admin-capability-source">
        <div><small>Profile digest</small><code>{model.profileDigest}</code></div>
      </section>
      <div className="admin-capability-tags">
        {model.capabilities.map((capability) => <span key={capability}>{capability}</span>)}
      </div>
    </article>
  );
}
