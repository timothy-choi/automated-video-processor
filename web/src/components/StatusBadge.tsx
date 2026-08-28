import type { JobStatus, OperationStatus, AttemptStatus } from "../types";

const LABELS: Record<string, string> = {
  QUEUED: "Queued",
  ASSIGNED: "Assigned",
  RUNNING: "Running",
  CANCEL_REQUESTED: "Cancel requested",
  COMPLETED: "Completed",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
  INTERRUPTED: "Interrupted",
};

export function StatusBadge({
  status,
}: {
  status: JobStatus | OperationStatus | AttemptStatus;
}) {
  const label = LABELS[status] ?? status.replaceAll("_", " ").toLowerCase();
  return (
    <span className={`badge badge-${status.toLowerCase()}`}>
      {label}
    </span>
  );
}
