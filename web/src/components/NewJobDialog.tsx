import { useState, type FormEvent } from "react";
import { ApiClientError } from "../api";
import { useAuth } from "../auth";
import { OPERATION_TYPES, PRIORITIES, type CreateJobRequest, type OperationType } from "../types";

export function NewJobDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (jobId: string) => void;
}) {
  const { api } = useAuth();
  const [inputUri, setInputUri] = useState("s3://media-input/sample.mp4");
  const [selected, setSelected] = useState<OperationType[]>(["METADATA", "THUMBNAIL"]);
  const [priority, setPriority] = useState<(typeof PRIORITIES)[number]>("NORMAL");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  if (!open) {
    return null;
  }

  function toggle(type: OperationType) {
    setSelected((current) =>
      current.includes(type) ? current.filter((item) => item !== type) : [...current, type]
    );
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!inputUri.trim() || selected.length === 0) {
      setError("Provide an object URI and at least one operation.");
      return;
    }
    const body: CreateJobRequest = {
      inputUri: inputUri.trim(),
      operations: selected.map((type) => ({ type })),
      priority,
    };
    setPending(true);
    try {
      const job = await api.createJob(body);
      onCreated(job.id);
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "Could not create Job.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="dialog-backdrop" role="presentation" onClick={onClose}>
      <div
        className="dialog"
        role="dialog"
        aria-labelledby="new-job-title"
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id="new-job-title">New Job</h2>
        <p className="muted">
          The object must already exist in object storage. This console does not upload media.
        </p>
        <form onSubmit={(event) => void submit(event)}>
          <label className="field">
            Input URI
            <input
              value={inputUri}
              onChange={(event) => setInputUri(event.target.value)}
              spellCheck={false}
              required
            />
          </label>
          <fieldset className="ops-picker">
            <legend>Operations</legend>
            {OPERATION_TYPES.map((type) => (
              <label key={type}>
                <input
                  type="checkbox"
                  checked={selected.includes(type)}
                  onChange={() => toggle(type)}
                />{" "}
                {type}
              </label>
            ))}
          </fieldset>
          <label className="field">
            Priority
            <select value={priority} onChange={(event) => setPriority(event.target.value as typeof priority)}>
              {PRIORITIES.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </label>
          {error ? <p className="banner banner-error">{error}</p> : null}
          <div className="inline">
            <button className="btn" type="submit" disabled={pending}>
              {pending ? "Submitting…" : "Submit Job"}
            </button>
            <button className="btn btn-secondary" type="button" onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
