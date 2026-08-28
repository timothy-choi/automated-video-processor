import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "../App";
import { emptyJobList, jsonResponse, renderConsole } from "../test/render";

const JOB_A = {
  id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  inputUri: "s3://media-input/a.mp4",
  status: "COMPLETED",
  priority: "NORMAL",
  deadline: null,
  createdAt: "2026-08-27T12:00:00Z",
  updatedAt: "2026-08-27T12:01:00Z",
  operationCount: 2,
  artifactCount: 1,
};

describe("Jobs page", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders Jobs from the list API", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          items: [JOB_A],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        })
      )
    );
    renderConsole(<App />);
    expect((await screen.findAllByText("aaaaaaaa")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Completed").length).toBeGreaterThan(0);
    expect(screen.getByRole("columnheader", { name: "Operations" })).toBeInTheDocument();
  });

  it("shows an empty state when there are no Jobs", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, emptyJobList())));
    renderConsole(<App />);
    expect(await screen.findByText("No Jobs yet.")).toBeInTheDocument();
  });

  it("requests the next page from the API", async () => {
    const user = userEvent.setup();
    const page0 = {
      items: [JOB_A],
      page: 0,
      size: 20,
      totalElements: 21,
      totalPages: 2,
    };
    const page1 = {
      items: [{ ...JOB_A, id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" }],
      page: 1,
      size: 20,
      totalElements: 21,
      totalPages: 2,
    };
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (String(url).includes("page=1")) {
        return jsonResponse(200, page1);
      }
      return jsonResponse(200, page0);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderConsole(<App />);
    expect((await screen.findAllByText("aaaaaaaa")).length).toBeGreaterThan(0);

    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findAllByText("bbbbbbbb")).not.toHaveLength(0);
    expect(fetchMock.mock.calls.some((call) => String(call[0]).includes("page=1&size=20"))).toBe(true);
  });
});
