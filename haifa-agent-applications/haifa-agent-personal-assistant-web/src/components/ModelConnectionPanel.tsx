import { X } from "lucide-react";
import { useEffect, useState } from "react";
import type { ModelConnection } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";
import { ModelConnectionTab } from "./ModelConnectionTab";

export interface ModelConnectionPanelProps {
  client: PersonalAssistantClient;
  open: boolean;
  providerId: string;
  onClose(): void;
  onConnectionsChanged?(connections: ModelConnection[]): void;
}

/** Legacy standalone connections modal; the account content lives in {@link ModelConnectionTab}. */
export function ModelConnectionPanel({
  client,
  open,
  providerId,
  onClose,
  onConnectionsChanged,
}: ModelConnectionPanelProps) {
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    if (open) setDismissed(false);
  }, [open]);

  if (!open || dismissed) return null;

  const close = () => {
    setDismissed(true);
    onClose();
  };

  return (
    <div className="model-connection-backdrop" role="presentation">
      <section className="model-connection-panel" role="dialog" aria-modal="true" aria-label="模型连接">
        <header>
          <div>
            <span>MODEL CONNECTIONS</span>
            <h2>连接模型</h2>
          </div>
          <button type="button" className="icon" aria-label="关闭模型连接" onClick={close}>
            <X size={18} />
          </button>
        </header>
        <ModelConnectionTab client={client} providerId={providerId} onConnectionsChanged={onConnectionsChanged} />
      </section>
    </div>
  );
}
