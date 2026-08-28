export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function formatTime(iso: string | undefined | null): string {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const ms = String(date.getMilliseconds()).padStart(3, "0");
  return `${date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  })}.${ms}`;
}

export function formatDuration(ms: number | undefined | null): string {
  if (ms == null || Number.isNaN(ms)) {
    return "—";
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  if (ms < 60_000) {
    const seconds = ms / 1000;
    const digits = seconds >= 10 ? 1 : 2;
    return `${seconds.toFixed(digits).replace(/\.?0+$/, "")} s`;
  }
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
}

export function formatBytes(bytes: number | undefined | null): string {
  if (bytes == null) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1).replace(/\.0$/, "")} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1).replace(/\.0$/, "")} MB`;
}

export function shortId(id: string): string {
  if (id.length <= 8) {
    return id;
  }
  return `${id.slice(0, 8)}…`;
}

export function firstLine(text: string | undefined | null): string | null {
  if (!text) {
    return null;
  }
  const line = text.split(/\r?\n/, 1)[0]?.trim() ?? "";
  return line.length === 0 ? null : line;
}

export const EVENT_LABELS: Record<string, string> = {
  JOB_CREATED: "Job created",
  OPERATION_QUEUED: "queued",
  OPERATION_RETRIED: "requeued after failure",
  OPERATION_ASSIGNED: "assigned",
  OPERATION_STARTED: "execution started",
  ARTIFACT_CREATED: "artifact created",
  OPERATION_COMPLETED: "operation completed",
  OPERATION_FAILED: "operation failed",
  OPERATION_CANCELLED: "operation cancelled",
  JOB_COMPLETED: "Job completed",
  JOB_FAILED: "Job failed",
  JOB_CANCELLED: "Job cancelled",
};
