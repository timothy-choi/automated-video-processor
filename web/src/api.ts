import type {
  ApiErrorBody,
  ArtifactDownloadResponse,
  AttemptResponse,
  CreateJobRequest,
  CreateMediaAssetRequest,
  CreateMediaAssetResponse,
  JobArtifactsResponse,
  JobListResponse,
  JobResponse,
  JobTimelineResponse,
  MediaAssetListResponse,
  MediaAssetResponse,
  MediaAssetStatus,
  OperationAttemptsResponse,
  OperationType,
  JobPriority,
  JobStatus,
  UploadUrlResponse,
} from "./types";

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
  }
}

export interface AuthBridge {
  getApiKey: () => string | null;
  onUnauthorized: () => void;
}

function messageForStatus(status: number, body: ApiErrorBody | null): string {
  if (status === 401) {
    return "Your API key is invalid or has been revoked.";
  }
  if (status === 403) {
    return body?.message || "You are not allowed to perform this action.";
  }
  if (status === 404) {
    if (body?.code === "MEDIA_ASSET_NOT_FOUND") {
      return body.message || "Media asset not found.";
    }
    return body?.code === "JOB_NOT_FOUND" || !body?.message ? "Job not found." : body.message;
  }
  if (status === 409) {
    return body?.message || "This Job cannot be changed in its current state.";
  }
  if (status >= 500) {
    return "The control service is unavailable. Try again shortly.";
  }
  return body?.message || `Request failed (${status}).`;
}

async function parseBody(response: Response): Promise<ApiErrorBody | null> {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    return null;
  }
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return null;
  }
}

export function createApiClient(auth: AuthBridge) {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body !== undefined && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    const apiKey = auth.getApiKey();
    if (apiKey) {
      headers.set("Authorization", `Bearer ${apiKey}`);
    }

    let response: Response;
    try {
      response = await fetch(`/api${path}`, { ...init, headers });
    } catch {
      throw new ApiClientError(0, "NETWORK", "Network error. Check that the platform is reachable.");
    }

    if (response.status === 401) {
      auth.onUnauthorized();
      throw new ApiClientError(401, "UNAUTHORIZED", messageForStatus(401, null));
    }

    if (!response.ok) {
      const body = await parseBody(response);
      throw new ApiClientError(
        response.status,
        body?.code ?? `HTTP_${response.status}`,
        messageForStatus(response.status, body)
      );
    }

    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  return {
    listJobs(query: {
      page?: number;
      size?: number;
      status?: JobStatus | "";
      operationType?: OperationType | "";
      priority?: JobPriority | "";
    } = {}): Promise<JobListResponse> {
      const params = new URLSearchParams();
      params.set("page", String(query.page ?? 0));
      params.set("size", String(query.size ?? 20));
      if (query.status) {
        params.set("status", query.status);
      }
      if (query.operationType) {
        params.set("operationType", query.operationType);
      }
      if (query.priority) {
        params.set("priority", query.priority);
      }
      return request(`/jobs?${params.toString()}`);
    },

    getJob(jobId: string): Promise<JobResponse> {
      return request(`/jobs/${jobId}`);
    },

    getTimeline(jobId: string): Promise<JobTimelineResponse> {
      return request(`/jobs/${jobId}/timeline`);
    },

    getAttempts(jobId: string, operationId: string): Promise<OperationAttemptsResponse> {
      return request(`/jobs/${jobId}/operations/${operationId}/attempts`);
    },

    getArtifacts(jobId: string): Promise<JobArtifactsResponse> {
      return request(`/jobs/${jobId}/artifacts`);
    },

    async createDownloadUrl(jobId: string, artifactId: string): Promise<ArtifactDownloadResponse> {
      return request(`/jobs/${jobId}/artifacts/${artifactId}/download-url`, { method: "POST" });
    },

    cancelJob(jobId: string): Promise<JobResponse> {
      return request(`/jobs/${jobId}/cancel`, { method: "POST" });
    },

    retryOperation(jobId: string, operationId: string): Promise<unknown> {
      return request(`/jobs/${jobId}/operations/${operationId}/retry`, { method: "POST" });
    },

    createJob(body: CreateJobRequest): Promise<JobResponse> {
      return request("/jobs", { method: "POST", body: JSON.stringify(body) });
    },

    listMediaAssets(query: {
      page?: number;
      size?: number;
      status?: MediaAssetStatus | "";
    } = {}): Promise<MediaAssetListResponse> {
      const params = new URLSearchParams();
      params.set("page", String(query.page ?? 0));
      params.set("size", String(query.size ?? 20));
      if (query.status) {
        params.set("status", query.status);
      }
      return request(`/media-assets?${params.toString()}`);
    },

    createMediaAsset(body: CreateMediaAssetRequest): Promise<CreateMediaAssetResponse> {
      return request("/media-assets", { method: "POST", body: JSON.stringify(body) });
    },

    completeMediaAsset(id: string): Promise<MediaAssetResponse> {
      return request(`/media-assets/${id}/complete`, { method: "POST" });
    },

    createMediaUploadUrl(id: string): Promise<UploadUrlResponse> {
      return request(`/media-assets/${id}/upload-url`, { method: "POST" });
    },

    async loadAttemptsForOperations(
      jobId: string,
      operationIds: string[]
    ): Promise<Record<string, AttemptResponse[]>> {
      const entries = await Promise.all(
        operationIds.map(async (operationId) => {
          const response = await this.getAttempts(jobId, operationId);
          return [operationId, response.attempts] as const;
        })
      );
      return Object.fromEntries(entries);
    },
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
