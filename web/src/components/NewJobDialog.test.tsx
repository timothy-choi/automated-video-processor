import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "../App";
import { emptyJobList, jsonResponse, renderConsole } from "../test/render";
import { putFileToPresignedUrl } from "../upload";

const FAKE_UPLOAD_URL =
  "https://127.0.0.1:9443/media-input/accounts/a/media/b/source?X-Amz-Signature=test";

vi.mock("../upload", async () => {
  const actual = await vi.importActual<typeof import("../upload")>("../upload");
  return {
    ...actual,
    putFileToPresignedUrl: vi.fn(),
  };
});

const createdJob = {
  id: "job-1",
  inputUri: "s3://media-input/accounts/a/media/asset-1/source",
  mediaAssetId: "asset-1",
  status: "QUEUED",
  priority: "NORMAL",
  deadline: null,
  createdAt: "2026-08-28T00:00:00Z",
  updatedAt: "2026-08-28T00:00:00Z",
  operationCount: 2,
  artifactCount: 0,
  operations: [],
};

function mockApis(handlers: {
  jobs?: unknown;
  mediaList?: unknown;
  createAsset?: (init?: RequestInit) => Response | Promise<Response>;
  complete?: () => Response | Promise<Response>;
  createJob?: (init?: RequestInit) => Response | Promise<Response>;
}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url);
    const method = (init?.method ?? "GET").toUpperCase();
    if (method === "GET" && (path === "/api/jobs" || path.startsWith("/api/jobs?"))) {
      return jsonResponse(200, handlers.jobs ?? emptyJobList());
    }
    if (method === "GET" && path === "/api/jobs/job-1") {
      return jsonResponse(200, createdJob);
    }
    if (method === "GET" && path === "/api/jobs/job-1/timeline") {
      return jsonResponse(200, {
        jobId: "job-1",
        status: "QUEUED",
        createdAt: "2026-08-28T00:00:00Z",
        updatedAt: "2026-08-28T00:00:00Z",
        operationCount: 0,
        completedOperationCount: 0,
        failedOperationCount: 0,
        cancelledOperationCount: 0,
        artifactCount: 0,
        events: [],
        operations: [],
      });
    }
    if (method === "GET" && path === "/api/jobs/job-1/artifacts") {
      return jsonResponse(200, { jobId: "job-1", artifacts: [] });
    }
    if (path.startsWith("/api/media-assets?") && method === "GET") {
      return jsonResponse(
        200,
        handlers.mediaList ?? { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
      );
    }
    if (path === "/api/media-assets" && method === "POST") {
      if (handlers.createAsset) {
        return handlers.createAsset(init);
      }
      return jsonResponse(201, {
        mediaAsset: {
          id: "asset-1",
          status: "PENDING_UPLOAD",
          filename: "clip.mp4",
          contentType: "video/mp4",
          sizeBytes: 4,
          createdAt: "2026-08-28T00:00:00Z",
          updatedAt: "2026-08-28T00:00:00Z",
        },
        upload: {
          method: "PUT",
          url: FAKE_UPLOAD_URL,
          expiresAt: "2026-08-28T00:15:00Z",
          headers: { "Content-Type": "video/mp4" },
        },
      });
    }
    if (path === "/api/media-assets/asset-1/complete" && method === "POST") {
      if (handlers.complete) {
        return handlers.complete();
      }
      return jsonResponse(200, {
        id: "asset-1",
        status: "READY",
        filename: "clip.mp4",
        contentType: "video/mp4",
        sizeBytes: 4,
        createdAt: "2026-08-28T00:00:00Z",
        updatedAt: "2026-08-28T00:00:00Z",
      });
    }
    if (path === "/api/jobs" && method === "POST") {
      if (handlers.createJob) {
        return handlers.createJob(init);
      }
      return jsonResponse(202, createdJob);
    }
    return jsonResponse(404, { code: "NOT_FOUND" });
  });
}

describe("New Job upload flow", () => {
  beforeEach(() => {
    vi.mocked(putFileToPresignedUrl).mockReset();
    vi.mocked(putFileToPresignedUrl).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uploads a local file then creates a Job from the READY asset", async () => {
    const user = userEvent.setup();
    const fetchMock = mockApis({});
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    await screen.findByText("No Jobs yet.");
    await user.click(screen.getByRole("button", { name: "New Job" }));
    const file = new File(["data"], "clip.mp4", { type: "video/mp4" });
    await user.upload(screen.getByLabelText("Choose file"), file);
    await user.click(screen.getByRole("button", { name: "Upload & Create Job" }));
    await waitFor(() => expect(putFileToPresignedUrl).toHaveBeenCalledOnce());
    const uploadArgs = vi.mocked(putFileToPresignedUrl).mock.calls[0][0];
    expect(uploadArgs.url).toBe(FAKE_UPLOAD_URL);
    expect(uploadArgs.file).toBe(file);
    expect(uploadArgs.headers).toEqual({ "Content-Type": "video/mp4" });
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
        )
      ).toBe(true)
    );
    const jobPost = fetchMock.mock.calls.find(
      (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
    );
    const body = JSON.parse(String((jobPost?.[1] as RequestInit).body)) as { mediaAssetId?: string; inputUri?: string };
    expect(body.mediaAssetId).toBe("asset-1");
    expect(body.inputUri).toBeUndefined();
    expect(await screen.findByRole("heading", { name: "Job" })).toBeInTheDocument();
  });

  it("shows a safe API error and does not upload", async () => {
    const user = userEvent.setup();
    const fetchMock = mockApis({
      createAsset: () =>
        jsonResponse(400, {
          code: "UPLOAD_TOO_LARGE",
          message: "Media exceeds the configured upload size limit",
        }),
    });
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    await screen.findByText("No Jobs yet.");
    await user.click(screen.getByRole("button", { name: "New Job" }));
    await user.upload(
      screen.getByLabelText("Choose file"),
      new File(["data"], "clip.mp4", { type: "video/mp4" })
    );
    await user.click(screen.getByRole("button", { name: "Upload & Create Job" }));
    expect(await screen.findByText("Media exceeds the configured upload size limit")).toBeInTheDocument();
    expect(putFileToPresignedUrl).not.toHaveBeenCalled();
    expect(
      fetchMock.mock.calls.some(
        (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
      )
    ).toBe(false);
  });

  it("does not complete or create a Job when PUT fails", async () => {
    const user = userEvent.setup();
    vi.mocked(putFileToPresignedUrl).mockRejectedValue(new Error("Upload to object storage failed."));
    const fetchMock = mockApis({});
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    await screen.findByText("No Jobs yet.");
    await user.click(screen.getByRole("button", { name: "New Job" }));
    await user.upload(
      screen.getByLabelText("Choose file"),
      new File(["data"], "clip.mp4", { type: "video/mp4" })
    );
    await user.click(screen.getByRole("button", { name: "Upload & Create Job" }));
    expect(await screen.findByText("Could not create Job.")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some((call) => String(call[0]).includes("/complete"))).toBe(false);
    expect(
      fetchMock.mock.calls.some(
        (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
      )
    ).toBe(false);
  });

  it("does not create a Job when complete fails", async () => {
    const user = userEvent.setup();
    const fetchMock = mockApis({
      complete: () =>
        jsonResponse(409, {
          code: "UPLOAD_OBJECT_NOT_FOUND",
          message: "Uploaded object was not found in object storage",
        }),
    });
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    await screen.findByText("No Jobs yet.");
    await user.click(screen.getByRole("button", { name: "New Job" }));
    await user.upload(
      screen.getByLabelText("Choose file"),
      new File(["data"], "clip.mp4", { type: "video/mp4" })
    );
    await user.click(screen.getByRole("button", { name: "Upload & Create Job" }));
    expect(await screen.findByText("Uploaded object was not found in object storage")).toBeInTheDocument();
    expect(
      fetchMock.mock.calls.some(
        (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
      )
    ).toBe(false);
  });

  it("blocks an obviously oversize file before calling the API", async () => {
    const user = userEvent.setup();
    const fetchMock = mockApis({});
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    await screen.findByText("No Jobs yet.");
    await user.click(screen.getByRole("button", { name: "New Job" }));
    const huge = new File(["x"], "huge.mp4", { type: "video/mp4" });
    Object.defineProperty(huge, "size", { value: 3 * 1024 * 1024 * 1024 });
    await user.upload(screen.getByLabelText("Choose file"), huge);
    await user.click(screen.getByRole("button", { name: "Upload & Create Job" }));
    expect(await screen.findByText("That file is larger than the 2 GiB upload limit.")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some((call) => String(call[0]) === "/api/media-assets")).toBe(false);
    expect(putFileToPresignedUrl).not.toHaveBeenCalled();
  });

  it("creates a Job from an existing READY asset without uploading", async () => {
    const user = userEvent.setup();
    const fetchMock = mockApis({
      mediaList: {
        items: [
          {
            id: "ready-1",
            status: "READY",
            filename: "prior.mp4",
            contentType: "video/mp4",
            sizeBytes: 1024,
            createdAt: "2026-08-28T00:00:00Z",
            updatedAt: "2026-08-28T00:00:00Z",
          },
        ],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      },
    });
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    await screen.findByText("No Jobs yet.");
    await user.click(screen.getByRole("button", { name: "New Job" }));
    await user.click(screen.getByLabelText("Choose existing READY media"));
    await user.selectOptions(screen.getByLabelText("READY media"), "ready-1");
    await user.click(screen.getByRole("button", { name: "Create Job" }));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
        )
      ).toBe(true)
    );
    const jobPost = fetchMock.mock.calls.find(
      (call) => String(call[0]) === "/api/jobs" && (call[1] as RequestInit | undefined)?.method === "POST"
    );
    const body = JSON.parse(String((jobPost?.[1] as RequestInit).body)) as { mediaAssetId?: string };
    expect(body.mediaAssetId).toBe("ready-1");
    expect(putFileToPresignedUrl).not.toHaveBeenCalled();
  });
});
