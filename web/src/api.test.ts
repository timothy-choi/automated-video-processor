import { afterEach, describe, expect, it, vi } from "vitest";
import { createApiClient, ApiClientError } from "./api";
import { jsonResponse } from "./test/render";

const TEST_API_KEY = "test-api-key";

describe("api client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends bearer auth and maps 401 without storing the response body as HTML", async () => {
    const onUnauthorized = vi.fn();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(401, { code: "UNAUTHORIZED" }));
    vi.stubGlobal("fetch", fetchMock);

    const api = createApiClient({
      getApiKey: () => TEST_API_KEY,
      onUnauthorized,
    });

    await expect(api.listJobs({ size: 1 })).rejects.toMatchObject({
      status: 401,
      message: "Your API key is invalid or has been revoked.",
    } satisfies Partial<ApiClientError>);
    expect(onUnauthorized).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledOnce();
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new Headers(init.headers).get("Authorization")).toBe(`Bearer ${TEST_API_KEY}`);
  });

  it("maps 404, 409, 5xx, and network errors", async () => {
    const api = createApiClient({
      getApiKey: () => TEST_API_KEY,
      onUnauthorized: vi.fn(),
    });

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(404, { code: "JOB_NOT_FOUND" })));
    await expect(api.getJob("missing")).rejects.toMatchObject({ message: "Job not found." });

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(409, { code: "ILLEGAL_STATE", message: "Operation is not FAILED." })
      )
    );
    await expect(api.retryOperation("job", "op")).rejects.toMatchObject({
      message: "Operation is not FAILED.",
    });

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>oops</html>", { status: 502 })));
    await expect(api.getJob("job")).rejects.toMatchObject({
      message: "The control service is unavailable. Try again shortly.",
    });

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    await expect(api.listJobs()).rejects.toMatchObject({
      message: "Network error. Check that the platform is reachable.",
    });
  });
});
