import { afterEach, describe, expect, it, vi } from "vitest";
import { putFileToPresignedUrl } from "./upload";

describe("putFileToPresignedUrl", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("PUTs the File/Blob without an Authorization header", async () => {
    const sentHeaders: string[] = [];
    class FakeXhr {
      status = 200;
      upload = { onprogress: null as ((event: ProgressEvent) => void) | null };
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      onabort: (() => void) | null = null;
      open = vi.fn();
      send = vi.fn((body: unknown) => {
        expect(body).toBe(file);
        this.onload?.();
      });
      setRequestHeader = vi.fn((name: string, value: string) => {
        sentHeaders.push(`${name}: ${value}`);
      });
    }
    vi.stubGlobal("XMLHttpRequest", FakeXhr);
    const file = new File(["abc"], "clip.mp4", { type: "video/mp4" });
    await putFileToPresignedUrl({
      url: "https://127.0.0.1:9443/media-input/accounts/x/media/y/source?X-Amz-Signature=test",
      file,
      headers: {
        "Content-Type": "video/mp4",
        Authorization: "Bearer should-not-be-sent",
      },
    });
    expect(sentHeaders).toEqual(["Content-Type: video/mp4"]);
  });
});
