import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiClientError } from "../api";
import { useAuth } from "../auth";
import { NewJobDialog } from "../components/NewJobDialog";
import { StatusBadge } from "../components/StatusBadge";
import { formatDateTime } from "../format";
import {
  JOB_STATUSES,
  OPERATION_TYPES,
  PRIORITIES,
  type JobPriority,
  type JobStatus,
  type JobSummary,
  type OperationType,
} from "../types";

const PAGE_SIZE = 20;

export function JobsPage() {
  const { api } = useAuth();
  const navigate = useNavigate();
  const [items, setItems] = useState<JobSummary[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [status, setStatus] = useState<JobStatus | "">("");
  const [operationType, setOperationType] = useState<OperationType | "">("");
  const [priority, setPriority] = useState<JobPriority | "">("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newJobOpen, setNewJobOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .listJobs({
        page,
        size: PAGE_SIZE,
        status,
        operationType,
        priority,
      })
      .then((response) => {
        if (cancelled) {
          return;
        }
        setItems(response.items);
        setTotalPages(response.totalPages);
        setTotalElements(response.totalElements);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof ApiClientError ? err.message : "Could not load Jobs.");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api, page, status, operationType, priority]);

  return (
    <main className="page" id="main">
      <div className="toolbar">
        <div>
          <h1>Jobs</h1>
          <p className="muted">{totalElements} owned Job{totalElements === 1 ? "" : "s"}</p>
        </div>
        <button className="btn" type="button" onClick={() => setNewJobOpen(true)}>
          New Job
        </button>
      </div>

      <div className="filters" style={{ marginBottom: 16 }}>
        <label className="field">
          Status
          <select
            value={status}
            onChange={(event) => {
              setPage(0);
              setStatus(event.target.value as JobStatus | "");
            }}
          >
            <option value="">Any</option>
            {JOB_STATUSES.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Operation type
          <select
            value={operationType}
            onChange={(event) => {
              setPage(0);
              setOperationType(event.target.value as OperationType | "");
            }}
          >
            <option value="">Any</option>
            {OPERATION_TYPES.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Priority
          <select
            value={priority}
            onChange={(event) => {
              setPage(0);
              setPriority(event.target.value as JobPriority | "");
            }}
          >
            <option value="">Any</option>
            {PRIORITIES.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
      </div>

      {error ? <p className="banner banner-error" role="alert">{error}</p> : null}
      {loading ? <p className="muted">Loading Jobs…</p> : null}

      {!loading && items.length === 0 ? (
        <div className="panel empty">No Jobs yet.</div>
      ) : null}

      {items.length > 0 ? (
        <>
          <div className="table-wrap panel" style={{ padding: 0 }}>
            <table className="jobs">
              <thead>
                <tr>
                  <th>Job</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th>Updated</th>
                  <th>Priority</th>
                  <th>Operations</th>
                  <th>Artifacts</th>
                </tr>
              </thead>
              <tbody>
                {items.map((job) => (
                  <tr key={job.id}>
                    <td>
                      <Link to={`/job/${job.id}`}>{shortLabel(job.id)}</Link>
                    </td>
                    <td>
                      <StatusBadge status={job.status} />
                    </td>
                    <td title={job.createdAt}>{formatDateTime(job.createdAt)}</td>
                    <td title={job.updatedAt}>{formatDateTime(job.updatedAt)}</td>
                    <td>{job.priority}</td>
                    <td>{job.operationCount}</td>
                    <td>{job.artifactCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="job-cards">
            {items.map((job) => (
              <Link key={job.id} className="job-card" to={`/job/${job.id}`}>
                <div className="inline">
                  <StatusBadge status={job.status} />
                  <strong>{shortLabel(job.id)}</strong>
                </div>
                <p className="muted">
                  {job.priority} · {job.operationCount} operations · {job.artifactCount} artifacts
                </p>
                <p className="muted">{formatDateTime(job.createdAt)}</p>
              </Link>
            ))}
          </div>
        </>
      ) : null}

      <div className="pager">
        <button className="btn btn-secondary" type="button" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <span className="muted">
          Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
        </span>
        <button
          className="btn btn-secondary"
          type="button"
          disabled={page + 1 >= totalPages}
          onClick={() => setPage((p) => p + 1)}
        >
          Next
        </button>
      </div>

      <NewJobDialog
        open={newJobOpen}
        onClose={() => setNewJobOpen(false)}
        onCreated={(jobId) => {
          setNewJobOpen(false);
          navigate(`/job/${jobId}`);
        }}
      />
    </main>
  );
}

function shortLabel(id: string) {
  return id.slice(0, 8);
}
