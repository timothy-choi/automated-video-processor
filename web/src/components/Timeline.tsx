import { EVENT_LABELS, firstLine, formatTime } from "../format";
import type { TimelineEvent } from "../types";

export function Timeline({ events }: { events: TimelineEvent[] }) {
  if (events.length === 0) {
    return <p className="empty">Timeline still empty.</p>;
  }
  const ordered = [...events].sort((a, b) => a.timestamp.localeCompare(b.timestamp));
  return (
    <ol className="timeline">
      {ordered.map((event, index) => {
        const failed = event.type === "OPERATION_FAILED" || event.type === "JOB_FAILED";
        const label = describeEvent(event);
        return (
          <li key={`${event.timestamp}-${event.type}-${index}`} className={failed ? "event-failed" : undefined}>
            <time dateTime={event.timestamp} title={event.timestamp}>
              {formatTime(event.timestamp)}
            </time>
            <div>{label}</div>
            {event.failureReason ? <div className="fail-reason">{firstLine(event.failureReason)}</div> : null}
          </li>
        );
      })}
    </ol>
  );
}

function describeEvent(event: TimelineEvent): string {
  const base = EVENT_LABELS[event.type] ?? event.type.replaceAll("_", " ").toLowerCase();
  if (event.operationType && event.type.startsWith("OPERATION_")) {
    if (event.type === "OPERATION_ASSIGNED" && event.workerId) {
      const policy = event.workerPolicy ? ` (${event.workerPolicy})` : "";
      return `${event.operationType} assigned to ${event.workerId}${policy}`;
    }
    if (event.type === "OPERATION_STARTED" && event.attemptNumber != null) {
      const worker = event.workerId ? ` on ${event.workerId}` : "";
      return `${event.operationType} execution started (attempt ${event.attemptNumber}${worker})`;
    }
    return `${event.operationType} ${base}`;
  }
  if (event.type === "ARTIFACT_CREATED" && event.artifactType) {
    return `${event.artifactType} artifact created`;
  }
  return event.message || base;
}
