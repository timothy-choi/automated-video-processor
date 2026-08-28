import { act } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "../App";
import { jsonResponse, renderConsole } from "../test/render";
import { JOB_DETAIL_POLL_MS } from "./JobDetailPage";

const JOB_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
const OP_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
const ARTIFACT_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

function jobPayload(status: string, operationStatus = status) {
  return {
    id: JOB_ID,
    inputUri: "s3://media-input/clip.mp4",
    status,
    priority: "NORMAL",
    deadline: null,
    createdAt: "2026-08-27T12:31:02.182Z",
    updatedAt: "2026-08-27T12:31:04.944Z",
    operationCount: 1,
    artifactCount: operationStatus === "COMPLETED" ? 1 : 0,
    operations: [
      {
        id: OP_ID,
        type: "AUDIO_EXTRACTION",
        status: operationStatus,
        order: 0,
        createdAt: "2026-08-27T12:31:02.200Z",
        updatedAt: "2026-08-27T12:31:04.944Z",
        failureReason: operationStatus === "FAILED" ? "input has no audio stream" : undefined,
      },
    ],
  };
}

function timelinePayload(jobStatus: string, operationStatus = jobStatus) {
  return {
    jobId: JOB_ID,
    status: jobStatus,
    createdAt: "2026-08-27T12:31:02.182Z",
    updatedAt: "2026-08-27T12:31:04.944Z",
    operationCount: 1,
    completedOperationCount: operationStatus === "COMPLETED" ? 1 : 0,
    failedOperationCount: operationStatus === "FAILED" ? 1 : 0,
    cancelledOperationCount: 0,
    artifactCount: 0,
    events: [
      {
        timestamp: "2026-08-27T12:31:04.944Z",
        type: "OPERATION_COMPLETED",
        operationType: "AUDIO_EXTRACTION",
      },
      {
        timestamp: "2026-08-27T12:31:02.182Z",
        type: "JOB_CREATED",
        message: "Job created",
      },
      {
        timestamp: "2026-08-27T12:31:02.486Z",
        type: "OPERATION_QUEUED",
        operationType: "AUDIO_EXTRACTION",
      },
    ],
    operations: [
      {
        operationId: OP_ID,
        type: "AUDIO_EXTRACTION",
        order: 0,
        status: operationStatus,
        queueWaitMs: 784,
        assignmentWaitMs: 1090,
        executionRuntimeMs: 1881,
        totalOperationLatencyMs: 3755,
        attemptCount: 1,
        lastWorkerId: "worker-a",
      },
    ],
  };
}

const emptyAttempts = { jobId: JOB_ID, operationId: OP_ID, attempts: [] };
const emptyArtifacts = { jobId: JOB_ID, artifacts: [] };

function mockJobFetch(jobStatus: string, operationStatus = jobStatus) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    if (url.includes("/download-url") && method === "POST") {
      return jsonResponse(200, {
        artifactId: ARTIFACT_ID,
        url: "http://127.0.0.1:9000/media-output/file.bin?X-Amz-Signature=test",
        expiresAt: "2026-08-27T13:00:00Z",
        fileName: "file.bin",
      });
    }
    if (url.endsWith(`/jobs/${JOB_ID}/cancel`) && method === "POST") {
      return jsonResponse(200, jobPayload("CANCEL_REQUESTED", "CANCEL_REQUESTED"));
    }
    if (url.includes("/retry") && method === "POST") {
      return jsonResponse(200, {
        jobId: JOB_ID,
        jobStatus: "QUEUED",
        operationId: OP_ID,
        operationStatus: "QUEUED",
        attemptCount: 1,
      });
    }
    if (url.endsWith(`/jobs/${JOB_ID}/timeline`)) {
      return jsonResponse(200, timelinePayload(jobStatus, operationStatus));
    }
    if (url.endsWith(`/jobs/${JOB_ID}/artifacts`)) {
      if (operationStatus === "COMPLETED") {
        return jsonResponse(200, {
          jobId: JOB_ID,
          artifacts: [
            {
              id: ARTIFACT_ID,
              operationId: OP_ID,
              type: "AUDIO",
              objectUri: "s3://media-output/out.m4a",
              contentType: "audio/mp4",
              sizeBytes: 2048,
              checksum: "abc123",
              createdAt: "2026-08-27T12:31:04.881Z",
            },
          ],
        });
      }
      return jsonResponse(200, emptyArtifacts);
    }
    if (url.includes("/attempts")) {
      if (operationStatus === "FAILED") {
        return jsonResponse(200, {
          jobId: JOB_ID,
          operationId: OP_ID,
          attempts: [
            {
              id: "attempt-1",
              attemptNumber: 1,
              workerId: "worker-a",
              status: "FAILED",
              createdAt: "2026-08-27T12:31:03.012Z",
              startedAt: "2026-08-27T12:31:03.201Z",
              endedAt: "2026-08-27T12:31:04.881Z",
              actualRuntimeMs: 1680,
              failureReason: "input has no audio stream",
            },
          ],
        });
      }
      return jsonResponse(200, emptyAttempts);
    }
    if (url.endsWith(`/jobs/${JOB_ID}`)) {
      return jsonResponse(200, jobPayload(jobStatus, operationStatus));
    }
    return jsonResponse(404, { code: "UNMOCKED", message: url });
  });
}

describe("Job detail", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders timeline chronologically, timings, and a failed reason", async () => {
    vi.stubGlobal("fetch", mockJobFetch("FAILED", "FAILED"));
    renderConsole(<App />, { path: `/job/${JOB_ID}` });

    expect(await screen.findByText("Job created")).toBeInTheDocument();
    const items = screen.getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("Job created");
    expect(items[1]).toHaveTextContent("AUDIO_EXTRACTION queued");
    expect(screen.getAllByText("784 ms").length).toBeGreaterThan(0);
    expect(screen.getAllByText("1.09 s").length).toBeGreaterThan(0);
    expect(screen.getAllByText("input has no audio stream").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("confirms and calls cancel", async () => {
    const user = userEvent.setup();
    const fetchMock = mockJobFetch("RUNNING", "RUNNING");
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderConsole(<App />, { path: `/job/${JOB_ID}` });
    expect(await screen.findByRole("button", { name: "Cancel Job" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Cancel Job" }));
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith(`/jobs/${JOB_ID}/cancel`) && (init as RequestInit | undefined)?.method === "POST")).toBe(true);
  });

  it("confirms and calls retry", async () => {
    const user = userEvent.setup();
    const fetchMock = mockJobFetch("FAILED", "FAILED");
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderConsole(<App />, { path: `/job/${JOB_ID}` });
    expect(await screen.findByRole("button", { name: "Retry" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(
      fetchMock.mock.calls.some(
        ([url, init]) =>
          String(url).endsWith(`/jobs/${JOB_ID}/operations/${OP_ID}/retry`) &&
          (init as RequestInit | undefined)?.method === "POST"
      )
    ).toBe(true);
  });

  it("requests a download URL and navigates to it without keeping it in UI state", async () => {
    const user = userEvent.setup();
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    vi.stubGlobal("fetch", mockJobFetch("COMPLETED", "COMPLETED"));
    renderConsole(<App />, { path: `/job/${JOB_ID}` });
    expect(await screen.findByRole("button", { name: "Download" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Download" }));
    expect(
      fetchMockCall(String.raw`/jobs/${JOB_ID}/artifacts/${ARTIFACT_ID}/download-url`)
    ).toBe(true);
    expect(assign).toHaveBeenCalledTimes(1);
    expect(String(assign.mock.calls[0]?.[0])).toContain("127.0.0.1:9000");
    expect(String(assign.mock.calls[0]?.[0])).toContain("X-Amz-Signature=test");
    expect(screen.queryByText(/X-Amz-Signature/)).not.toBeInTheDocument();
  });

  it("polls a non-terminal Job and stops after it becomes terminal", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    let status = "RUNNING";
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith(`/jobs/${JOB_ID}/timeline`)) {
        return jsonResponse(200, timelinePayload(status, status));
      }
      if (url.endsWith(`/jobs/${JOB_ID}/artifacts`)) {
        return jsonResponse(200, emptyArtifacts);
      }
      if (url.includes("/attempts")) {
        return jsonResponse(200, emptyAttempts);
      }
      if (url.endsWith(`/jobs/${JOB_ID}`)) {
        return jsonResponse(200, jobPayload(status, status));
      }
      return jsonResponse(404, { code: "UNMOCKED" });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />, { path: `/job/${JOB_ID}` });
    expect((await screen.findAllByText("Running")).length).toBeGreaterThan(0);
    const afterInitial = fetchMock.mock.calls.filter(([url]) => String(url).endsWith(`/jobs/${JOB_ID}`)).length;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(JOB_DETAIL_POLL_MS);
    });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith(`/jobs/${JOB_ID}`)).length).toBeGreaterThan(afterInitial);

    status = "COMPLETED";
    await act(async () => {
      await vi.advanceTimersByTimeAsync(JOB_DETAIL_POLL_MS);
    });
    expect((await screen.findAllByText("Completed")).length).toBeGreaterThan(0);
    const afterTerminal = fetchMock.mock.calls.filter(([url]) => String(url).endsWith(`/jobs/${JOB_ID}`)).length;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(JOB_DETAIL_POLL_MS * 2);
    });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith(`/jobs/${JOB_ID}`)).length).toBe(afterTerminal);
  });
});

function fetchMockCall(suffix: string): boolean {
  const fetchMock = vi.mocked(fetch);
  return fetchMock.mock.calls.some(
    ([url, init]) => String(url).endsWith(suffix) && (init as RequestInit | undefined)?.method === "POST"
  );
}
