import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiClientError } from "../api";
import { useAuth } from "../auth";
import { CopyValue } from "../components/CopyValue";
import { StatusBadge } from "../components/StatusBadge";
import { Timeline } from "../components/Timeline";
import { firstLine, formatBytes, formatDateTime, formatDuration } from "../format";
import {
  isCancelableJob,
  isRetryableOperation,
  isTerminalJob,
  type ArtifactResponse,
  type AttemptResponse,
  type JobResponse,
  type JobTimelineResponse,
} from "../types";

export const JOB_DETAIL_POLL_MS = 3000;

export function JobDetailPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const { api } = useAuth();
  const [job, setJob] = useState<JobResponse | null>(null);
  const [timeline, setTimeline] = useState<JobTimelineResponse | null>(null);
  const [artifacts, setArtifacts] = useState<ArtifactResponse[] | null>(null);
  const [attemptsByOp, setAttemptsByOp] = useState<Record<string, AttemptResponse[]>>({});
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);
  const snapshotRef = useRef({ artifactCount: -1, statusKey: "" });

  const load = useCallback(
    async (mode: "full" | "poll") => {
      if (!jobId) {
        return;
      }
      if (mode === "full") {
        setRefreshing(true);
      }
      try {
        const [jobResult, timelineResult] = await Promise.all([api.getJob(jobId), api.getTimeline(jobId)]);
        const statusKey = jobResult.operations.map((operation) => `${operation.id}:${operation.status}`).join("|");
        const snapshot = snapshotRef.current;
        const shouldLoadArtifacts =
          mode === "full" || snapshot.artifactCount !== jobResult.artifactCount;
        const shouldLoadAttempts = mode === "full" || snapshot.statusKey !== statusKey;

        const artifactsResult = shouldLoadArtifacts
          ? (await api.getArtifacts(jobId)).artifacts
          : null;
        const attempts = shouldLoadAttempts
          ? await api.loadAttemptsForOperations(
              jobId,
              jobResult.operations.map((operation) => operation.id)
            )
          : null;

        snapshotRef.current = {
          artifactCount: jobResult.artifactCount,
          statusKey,
        };
        setJob(jobResult);
        setTimeline(timelineResult);
        if (artifactsResult) {
          setArtifacts(artifactsResult);
        }
        if (attempts) {
          setAttemptsByOp(attempts);
        }
        setLoadError(null);
        setLastRefreshed(new Date());
      } catch (err) {
        setLoadError(err instanceof ApiClientError ? err.message : "Unable to load Job.");
      } finally {
        setRefreshing(false);
      }
    },
    [api, jobId]
  );

  useEffect(() => {
    snapshotRef.current = { artifactCount: -1, statusKey: "" };
    void load("full");
  }, [load]);

  useEffect(() => {
    if (!job || isTerminalJob(job.status)) {
      return;
    }
    const intervalId = window.setInterval(() => {
      void load("poll");
    }, JOB_DETAIL_POLL_MS);
    return () => window.clearInterval(intervalId);
  }, [job?.id, job?.status, load]);

  async function onCancel() {
    if (!job || !window.confirm("Cancel this Job? In-flight work may still finish.")) {
      return;
    }
    setActionError(null);
    try {
      await api.cancelJob(job.id);
      await load("full");
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : "Cancel failed.");
    }
  }

  async function onRetry(operationId: string) {
    if (!window.confirm("Retry this failed operation?")) {
      return;
    }
    setActionError(null);
    try {
      await api.retryOperation(job!.id, operationId);
      await load("full");
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : "Retry failed.");
    }
  }

  async function onDownload(artifactId: string) {
    setDownloading(artifactId);
    setActionError(null);
    try {
      const { url } = await api.createDownloadUrl(job!.id, artifactId);
      window.location.assign(url);
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : "Download failed.");
    } finally {
      setDownloading(null);
    }
  }

  if (!jobId) {
    return (
      <main className="page">
        <p className="banner banner-error">Missing Job ID.</p>
      </main>
    );
  }
  if (loadError && !job) {
    return (
      <main className="page" id="main">
        <p>
          <Link to="/">← Jobs</Link>
        </p>
        <p className="banner banner-error" role="alert">
          {loadError}
        </p>
      </main>
    );
  }
  if (!job || !timeline) {
    return (
      <main className="page" id="main">
        <p className="muted">Loading Job…</p>
      </main>
    );
  }

  const canCancel = isCancelableJob(job.status) && job.status !== "CANCEL_REQUESTED";
  const lastRefreshLabel = lastRefreshed
    ? lastRefreshed.toLocaleTimeString(undefined, { hour12: false })
    : null;

  return (
    <main className="page" id="main">
      <p>
        <Link to="/">← Jobs</Link>
      </p>
      <div className="toolbar">
        <div>
          <h1>Job</h1>
          <CopyValue value={job.id} label="Job ID" />
        </div>
        {canCancel ? (
          <button className="btn btn-danger" type="button" onClick={() => void onCancel()}>
            Cancel Job
          </button>
        ) : null}
      </div>
      <p className="poll-note" aria-live="polite">
        {refreshing ? "Updating…" : lastRefreshLabel ? `Last refreshed ${lastRefreshLabel}` : null}
        {job.status === "CANCEL_REQUESTED" ? " · Cancellation requested" : null}
      </p>
      {actionError ? (
        <p className="banner banner-error" role="alert">
          {actionError}
        </p>
      ) : null}

      <section>
        <h2>Status</h2>
        <dl className="meta-grid">
          <div>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={job.status} />
            </dd>
          </div>
          <div>
            <dt>Created</dt>
            <dd>
              <span title={job.createdAt}>{formatDateTime(job.createdAt)}</span>
            </dd>
          </div>
          <div>
            <dt>Updated</dt>
            <dd>
              <span title={job.updatedAt}>{formatDateTime(job.updatedAt)}</span>
            </dd>
          </div>
          <div>
            <dt>Priority</dt>
            <dd>{job.priority}</dd>
          </div>
          <div>
            <dt>Deadline</dt>
            <dd>{job.deadline ? formatDateTime(job.deadline) : "—"}</dd>
          </div>
          <div>
            <dt>Input</dt>
            <dd>{job.inputUri}</dd>
          </div>
          <div>
            <dt>Operations</dt>
            <dd>{job.operationCount}</dd>
          </div>
          <div>
            <dt>Artifacts</dt>
            <dd>{job.artifactCount}</dd>
          </div>
        </dl>
      </section>

      <section className="section">
        <h2>Operation timings</h2>
        {timeline.operations.length === 0 ? (
          <p className="muted">Timing appears as operations progress.</p>
        ) : (
          <div className="table-wrap panel" style={{ padding: 0 }}>
            <table className="jobs">
              <thead>
                <tr>
                  <th>Operation</th>
                  <th>Queue wait</th>
                  <th>Assignment wait</th>
                  <th>Runtime</th>
                  <th>Total latency</th>
                </tr>
              </thead>
              <tbody>
                {timeline.operations.map((row) => (
                  <tr key={row.operationId}>
                    <td>{row.type}</td>
                    <td>{formatDuration(row.queueWaitMs)}</td>
                    <td>{formatDuration(row.assignmentWaitMs)}</td>
                    <td>{formatDuration(row.executionRuntimeMs)}</td>
                    <td>{formatDuration(row.totalOperationLatencyMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="section">
        <h2>Operations</h2>
        {job.operations.length === 0 ? (
          <p className="empty">No operations on this Job.</p>
        ) : (
          <div className="ops">
            {job.operations.map((operation) => {
              const timing = timeline.operations.find((row) => row.operationId === operation.id);
              const attempts = attemptsByOp[operation.id] ?? [];
              const failure = firstLine(operation.failureReason);
              return (
                <article key={operation.id} className="op-card">
                  <div className="inline">
                    <strong>{operation.type}</strong>
                    <StatusBadge status={operation.status} />
                    {isRetryableOperation(operation.status) ? (
                      <button className="btn" type="button" onClick={() => void onRetry(operation.id)}>
                        Retry
                      </button>
                    ) : null}
                  </div>
                  <div className="kv">
                    <div>
                      <span>Worker</span>
                      {timing?.lastWorkerId ?? "—"}
                    </div>
                    <div>
                      <span>Queue wait</span>
                      {formatDuration(timing?.queueWaitMs)}
                    </div>
                    <div>
                      <span>Assignment wait</span>
                      {formatDuration(timing?.assignmentWaitMs)}
                    </div>
                    <div>
                      <span>Runtime</span>
                      {formatDuration(timing?.executionRuntimeMs ?? operation.actualRuntimeMs)}
                    </div>
                    <div>
                      <span>Attempts</span>
                      {timing?.attemptCount ?? attempts.length}
                    </div>
                  </div>
                  <p>
                    <CopyValue value={operation.id} label="Operation ID" />
                  </p>
                  {operation.status === "FAILED" && failure ? (
                    <div className="fail-reason">
                      <strong>Failed</strong>
                      <div>{failure}</div>
                    </div>
                  ) : null}
                  {attempts.length === 0 ? (
                    <p className="muted">No attempts yet.</p>
                  ) : (
                    <div className="attempts">
                      {attempts.map((attempt) => (
                        <div key={attempt.id} className="attempt">
                          <div className="inline">
                            <strong>Attempt {attempt.attemptNumber}</strong>
                            <StatusBadge status={attempt.status} />
                          </div>
                          <p className="muted">
                            Worker {attempt.workerId || "—"}
                            {attempt.startedAt ? ` · Started ${formatDateTime(attempt.startedAt)}` : ""}
                            {attempt.endedAt ? ` · Ended ${formatDateTime(attempt.endedAt)}` : ""}
                            {attempt.actualRuntimeMs != null
                              ? ` · Runtime ${formatDuration(attempt.actualRuntimeMs)}`
                              : ""}
                          </p>
                          {attempt.failureReason ? (
                            <p className="fail-reason">{firstLine(attempt.failureReason)}</p>
                          ) : null}
                        </div>
                      ))}
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        )}
      </section>

      <section className="section">
        <h2>Timeline</h2>
        <Timeline events={timeline.events} />
      </section>

      <section className="section">
        <h2>Artifacts</h2>
        {artifacts === null ? (
          <p className="muted">Loading artifacts…</p>
        ) : artifacts.length === 0 ? (
          <p className="empty">No artifacts yet.</p>
        ) : (
          <div className="ops">
            {artifacts.map((artifact) => (
              <article key={artifact.id} className="artifact-card">
                <div className="inline" style={{ justifyContent: "space-between" }}>
                  <div>
                    <strong>{artifact.type}</strong>
                    <p className="muted">
                      {formatBytes(artifact.sizeBytes)}
                      {artifact.createdAt ? ` · ${formatDateTime(artifact.createdAt)}` : ""}
                    </p>
                    <p>
                      <CopyValue value={artifact.checksum} label="checksum" />
                    </p>
                  </div>
                  <button
                    className="btn"
                    type="button"
                    disabled={downloading === artifact.id}
                    onClick={() => void onDownload(artifact.id)}
                  >
                    {downloading === artifact.id ? "Preparing…" : "Download"}
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
