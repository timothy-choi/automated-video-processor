import { describe, expect, it } from "vitest";
import { formatDuration } from "./format";

describe("formatDuration", () => {
  it("humanizes millisecond durations from the API", () => {
    expect(formatDuration(784)).toBe("784 ms");
    expect(formatDuration(1090)).toBe("1.09 s");
    expect(formatDuration(134000)).toBe("2m 14s");
    expect(formatDuration(null)).toBe("—");
  });
});
