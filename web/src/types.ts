export const JOB_STATUSES = [
  "QUEUED",
  "ASSIGNED",
  "RUNNING",
  "CANCEL_REQUESTED",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "INTERRUPTED",
] as const;

export type JobStatus = (typeof JOB_STATUSES)[number];

export const OPERATION_STATUSES = [
  "QUEUED",
  "ASSIGNED",
  "RUNNING",
  "CANCEL_REQUESTED",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
] as const;

export type OperationStatus = (typeof OPERATION_STATUSES)[number];

export const OPERATION_TYPES = [
  "METADATA",
  "THUMBNAIL",
  "AUDIO_EXTRACTION",
  "TRANSCODE_1080P",
  "H264_TO_AV1",
] as const;

export type OperationType = (typeof OPERATION_TYPES)[number];

export const PRIORITIES = ["LOW", "NORMAL", "HIGH"] as const;
export type JobPriority = (typeof PRIORITIES)[number];

export type AttemptStatus =
  | "ASSIGNED"
  | "RUNNING"
  | "COMPLETED"
  | "FAILED"
  | "INTERRUPTED"
  | "CANCELLED";

export type ArtifactType = "THUMBNAIL" | "AUDIO" | "TRANSCODE_1080P" | "H264_TO_AV1";

export type TimelineEventType =
  | "JOB_CREATED"
  | "OPERATION_QUEUED"
  | "OPERATION_RETRIED"
  | "OPERATION_ASSIGNED"
  | "OPERATION_STARTED"
  | "ARTIFACT_CREATED"
  | "OPERATION_COMPLETED"
  | "OPERATION_FAILED"
  | "OPERATION_CANCELLED"
  | "JOB_COMPLETED"
  | "JOB_FAILED"
  | "JOB_CANCELLED";

export interface JobSummary {
  id: string;
  inputUri: string;
  mediaAssetId?: string | null;
  status: JobStatus;
  priority: JobPriority;
  deadline: string | null;
  createdAt: string;
  updatedAt: string;
  operationCount: number;
  artifactCount: number;
}

export interface JobListResponse {
  items: JobSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface OperationResponse {
  id: string;
  type: OperationType;
  status: OperationStatus;
  order: number;
  createdAt: string;
  queuedAt?: string;
  updatedAt: string;
  startedAt?: string;
  completedAt?: string;
  actualRuntimeMs?: number;
  failureReason?: string;
  result?: Record<string, unknown>;
}

export interface JobResponse {
  id: string;
  inputUri: string;
  mediaAssetId?: string | null;
  status: JobStatus;
  priority: JobPriority;
  deadline: string | null;
  createdAt: string;
  updatedAt: string;
  operationCount: number;
  artifactCount: number;
  operations: OperationResponse[];
}

export interface TimelineEvent {
  timestamp: string;
  type: TimelineEventType;
  message?: string;
  operationId?: string;
  operationType?: OperationType;
  operationOrder?: number;
  attemptId?: string;
  attemptNumber?: number;
  outcome?: AttemptStatus;
  workerId?: string;
  operationPolicy?: string;
  workerPolicy?: string;
  artifactId?: string;
  artifactType?: ArtifactType;
  sizeBytes?: number;
  checksum?: string;
  runtimeMs?: number;
  failureReason?: string;
}

export interface OperationTiming {
  operationId: string;
  type: OperationType;
  order: number;
  status: OperationStatus;
  queuedAt?: string;
  assignedAt?: string;
  startedAt?: string;
  completedAt?: string;
  queueWaitMs?: number;
  assignmentWaitMs?: number;
  executionRuntimeMs?: number;
  totalOperationLatencyMs?: number;
  attemptCount?: number;
  lastWorkerId?: string;
}

export interface JobTimelineResponse {
  jobId: string;
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
  durationMs?: number;
  operationCount: number;
  completedOperationCount: number;
  failedOperationCount: number;
  cancelledOperationCount: number;
  artifactCount: number;
  traceId?: string;
  events: TimelineEvent[];
  operations: OperationTiming[];
}

export interface AttemptResponse {
  id: string;
  attemptNumber: number;
  workerId: string;
  status: AttemptStatus;
  createdAt: string;
  startedAt: string;
  endedAt?: string;
  actualRuntimeMs?: number;
  failureReason?: string;
}

export interface OperationAttemptsResponse {
  jobId: string;
  operationId: string;
  attempts: AttemptResponse[];
}

export interface ArtifactResponse {
  id: string;
  operationId: string;
  type: ArtifactType;
  objectUri: string;
  contentType: string;
  sizeBytes: number;
  checksum: string;
  createdAt: string;
}

export interface JobArtifactsResponse {
  jobId: string;
  artifacts: ArtifactResponse[];
}

export interface ArtifactDownloadResponse {
  artifactId: string;
  url: string;
  expiresAt: string;
  contentType?: string;
  fileName?: string;
}

export interface CreateJobRequest {
  inputUri?: string;
  mediaAssetId?: string;
  operations: { type: OperationType }[];
  priority?: JobPriority;
}

export type MediaAssetStatus = "PENDING_UPLOAD" | "READY" | "FAILED";

export interface MediaAssetResponse {
  id: string;
  status: MediaAssetStatus;
  filename: string;
  contentType?: string | null;
  sizeBytes?: number | null;
  objectUri?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MediaAssetListResponse {
  items: MediaAssetResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UploadUrlResponse {
  method: "PUT" | string;
  url: string;
  expiresAt: string;
  headers?: Record<string, string>;
}

export interface CreateMediaAssetResponse {
  mediaAsset: MediaAssetResponse;
  upload: UploadUrlResponse;
}

export interface CreateMediaAssetRequest {
  filename: string;
  contentType?: string;
  sizeBytes: number;
}

export interface ApiErrorBody {
  code?: string;
  message?: string;
  timestamp?: string;
}

export const TERMINAL_JOB_STATUSES: ReadonlySet<JobStatus> = new Set([
  "COMPLETED",
  "FAILED",
  "CANCELLED",
]);

export function isTerminalJob(status: JobStatus): boolean {
  return TERMINAL_JOB_STATUSES.has(status);
}

export function isCancelableJob(status: JobStatus): boolean {
  return status === "QUEUED" || status === "ASSIGNED" || status === "RUNNING" || status === "CANCEL_REQUESTED";
}

export function isRetryableOperation(status: OperationStatus): boolean {
  return status === "FAILED";
}
