import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiClientError } from "../api";
import { useAuth } from "../auth";
import { formatBytes } from "../format";
import {
  OPERATION_TYPES,
  PRIORITIES,
  type CreateJobRequest,
  type MediaAssetResponse,
  type OperationType,
} from "../types";
import { DEFAULT_MEDIA_UPLOAD_MAX_BYTES, putFileToPresignedUrl, StorageUploadError } from "../upload";

type SourceMode = "upload" | "existing" | "advanced";
type UploadStage = "idle" | "preparing" | "uploading" | "verifying" | "creating";

const STAGE_LABEL: Record<Exclude<UploadStage, "idle">, string> = {
  preparing: "Preparing upload…",
  uploading: "Uploading…",
  verifying: "Verifying upload…",
  creating: "Creating Job…",
};

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
  const [sourceMode, setSourceMode] = useState<SourceMode>("upload");
  const [file, setFile] = useState<File | null>(null);
  const [existing, setExisting] = useState<MediaAssetResponse[]>([]);
  const [existingId, setExistingId] = useState("");
  const [inputUri, setInputUri] = useState("");
  const [selected, setSelected] = useState<OperationType[]>(["METADATA", "THUMBNAIL"]);
  const [priority, setPriority] = useState<(typeof PRIORITIES)[number]>("NORMAL");
  const [error, setError] = useState<string | null>(null);
  const [stage, setStage] = useState<UploadStage>("idle");
  const [progress, setProgress] = useState<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    let cancelled = false;
    api
      .listMediaAssets({ status: "READY", size: 50 })
      .then((response) => {
        if (!cancelled) {
          setExisting(response.items);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setExisting([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api, open]);

  if (!open) {
    return null;
  }

  const pending = stage !== "idle";

  function toggle(type: OperationType) {
    setSelected((current) =>
      current.includes(type) ? current.filter((item) => item !== type) : [...current, type]
    );
  }

  function resetAndClose() {
    abortRef.current?.abort();
    onClose();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (selected.length === 0) {
      setError("Select at least one operation.");
      return;
    }

    const operations = selected.map((type) => ({ type }));
    try {
      if (sourceMode === "advanced") {
        if (!inputUri.trim()) {
          setError("Provide an object URI.");
          return;
        }
        setStage("creating");
        const job = await api.createJob({
          inputUri: inputUri.trim(),
          operations,
          priority,
        });
        onCreated(job.id);
        return;
      }

      if (sourceMode === "existing") {
        if (!existingId) {
          setError("Choose an existing media asset.");
          return;
        }
        setStage("creating");
        const job = await api.createJob({
          mediaAssetId: existingId,
          operations,
          priority,
        });
        onCreated(job.id);
        return;
      }

      if (!file) {
        setError("Choose a media file to upload.");
        return;
      }
      if (file.size > DEFAULT_MEDIA_UPLOAD_MAX_BYTES) {
        setError("That file is larger than the 2 GiB upload limit.");
        return;
      }

      const contentType = file.type && file.type.length > 0 ? file.type : "application/octet-stream";
      setStage("preparing");
      const created = await api.createMediaAsset({
        filename: file.name,
        contentType,
        sizeBytes: file.size,
      });

      setStage("uploading");
      setProgress(0);
      const abort = new AbortController();
      abortRef.current = abort;
      await putFileToPresignedUrl({
        url: created.upload.url,
        file,
        headers: created.upload.headers ?? { "Content-Type": contentType },
        onProgress: setProgress,
        signal: abort.signal,
      });

      setStage("verifying");
      setProgress(null);
      await api.completeMediaAsset(created.mediaAsset.id);

      setStage("creating");
      const body: CreateJobRequest = {
        mediaAssetId: created.mediaAsset.id,
        operations,
        priority,
      };
      const job = await api.createJob(body);
      onCreated(job.id);
    } catch (err) {
      if (err instanceof StorageUploadError && err.aborted) {
        setError("Upload cancelled.");
      } else if (err instanceof ApiClientError) {
        setError(err.message);
      } else {
        setError("Could not create Job.");
      }
    } finally {
      abortRef.current = null;
      setStage("idle");
      setProgress(null);
    }
  }

  return (
    <div className="dialog-backdrop" role="presentation" onClick={pending ? undefined : resetAndClose}>
      <div
        className="dialog"
        role="dialog"
        aria-labelledby="new-job-title"
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id="new-job-title">New Job</h2>
        <form onSubmit={(event) => void submit(event)}>
          <fieldset className="ops-picker" disabled={pending}>
            <legend>Source Media</legend>
            <label>
              <input
                type="radio"
                name="source"
                checked={sourceMode === "upload"}
                onChange={() => setSourceMode("upload")}
              />{" "}
              Upload new media
            </label>
            <label>
              <input
                type="radio"
                name="source"
                checked={sourceMode === "existing"}
                onChange={() => setSourceMode("existing")}
              />{" "}
              Choose existing READY media
            </label>
            <label>
              <input
                type="radio"
                name="source"
                checked={sourceMode === "advanced"}
                onChange={() => setSourceMode("advanced")}
              />{" "}
              Advanced (object URI)
            </label>
          </fieldset>

          {sourceMode === "upload" ? (
            <label className="field">
              Choose file
              <input
                type="file"
                accept="video/*,audio/*,.mp4,.mov,.mkv,.webm,.m4a,.aac,.mp3"
                disabled={pending}
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
            </label>
          ) : null}

          {sourceMode === "upload" && file ? (
            <p className="muted">
              {file.name} · {formatBytes(file.size)}
            </p>
          ) : null}

          {sourceMode === "existing" ? (
            <label className="field">
              READY media
              <select
                value={existingId}
                disabled={pending}
                onChange={(event) => setExistingId(event.target.value)}
              >
                <option value="">Select…</option>
                {existing.map((asset) => (
                  <option key={asset.id} value={asset.id}>
                    {asset.filename} ({formatBytes(asset.sizeBytes)})
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {sourceMode === "advanced" ? (
            <label className="field">
              Input URI
              <input
                value={inputUri}
                onChange={(event) => setInputUri(event.target.value)}
                spellCheck={false}
                placeholder="s3://media-input/sample.mp4"
                disabled={pending}
              />
            </label>
          ) : null}

          <fieldset className="ops-picker" disabled={pending}>
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
            <select
              value={priority}
              disabled={pending}
              onChange={(event) => setPriority(event.target.value as typeof priority)}
            >
              {PRIORITIES.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </label>
          {stage !== "idle" ? (
            <p className="muted" role="status">
              {STAGE_LABEL[stage]}
              {stage === "uploading" && progress != null ? ` ${progress}%` : ""}
            </p>
          ) : null}
          {stage === "uploading" && progress != null ? (
            <progress className="upload-progress" max={100} value={progress} />
          ) : null}
          {error ? <p className="banner banner-error">{error}</p> : null}
          <div className="inline">
            <button className="btn" type="submit" disabled={pending}>
              {pending ? STAGE_LABEL[stage] : sourceMode === "upload" ? "Upload & Create Job" : "Create Job"}
            </button>
            {stage === "uploading" ? (
              <button
                className="btn btn-secondary"
                type="button"
                onClick={() => abortRef.current?.abort()}
              >
                Cancel upload
              </button>
            ) : (
              <button className="btn btn-secondary" type="button" onClick={resetAndClose} disabled={pending}>
                Cancel
              </button>
            )}
          </div>
        </form>
      </div>
    </div>
  );
}
