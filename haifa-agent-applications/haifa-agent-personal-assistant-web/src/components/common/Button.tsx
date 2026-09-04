import type { ButtonHTMLAttributes } from "react";
import { RefreshCw } from "lucide-react";

export function Button({
  children,
  busy = false,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }) {
  return (
    <button {...props} disabled={props.disabled || busy}>
      {busy ? <RefreshCw className="spin" size={16} aria-hidden="true" /> : children}
    </button>
  );
}
