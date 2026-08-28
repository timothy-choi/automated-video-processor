import { useState } from "react";
import { shortId } from "../format";

export function CopyValue({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      setCopied(false);
    }
  }

  return (
    <span className="inline">
      <span className="mono" title={value}>
        {shortId(value)}
      </span>
      <button type="button" className="copy" onClick={() => void copy()} aria-label={`Copy ${label}`}>
        {copied ? "Copied" : "Copy"}
      </button>
    </span>
  );
}
