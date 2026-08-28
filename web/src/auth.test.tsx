import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { App } from "./App";
import { AuthProvider } from "./auth";
import { emptyJobList, jsonResponse, TEST_API_KEY } from "./test/render";

function renderApp() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>
  );
}

describe("authentication", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("connects with a valid key and enters the Jobs console", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, emptyJobList(0, 1)))
      .mockResolvedValue(jsonResponse(200, emptyJobList()));
    vi.stubGlobal("fetch", fetchMock);
    renderApp();

    await user.type(screen.getByLabelText("API Key"), TEST_API_KEY);
    await user.click(screen.getByRole("button", { name: "Connect" }));

    expect(await screen.findByRole("heading", { name: "Jobs" })).toBeInTheDocument();
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/jobs?page=0&size=1");
  });

  it("shows an invalid-key error on 401 and stays on the connect screen", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, { code: "UNAUTHORIZED" })));
    renderApp();

    await user.type(screen.getByLabelText("API Key"), TEST_API_KEY);
    await user.click(screen.getByRole("button", { name: "Connect" }));

    expect(await screen.findByText("Your API key is invalid or has been revoked.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Connect" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Jobs" })).not.toBeInTheDocument();
  });

  it("clears credentials on Disconnect", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, emptyJobList(0, 1)))
      .mockResolvedValue(jsonResponse(200, emptyJobList()));
    vi.stubGlobal("fetch", fetchMock);
    renderApp();

    await user.type(screen.getByLabelText("API Key"), TEST_API_KEY);
    await user.click(screen.getByRole("button", { name: "Connect" }));
    expect(await screen.findByRole("button", { name: "Disconnect" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Disconnect" }));
    expect(screen.getByRole("button", { name: "Connect" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Jobs" })).not.toBeInTheDocument();
  });

  it("returns to the connect screen when a later request is 401", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, emptyJobList(0, 1)))
      .mockResolvedValueOnce(jsonResponse(200, emptyJobList()))
      .mockResolvedValue(jsonResponse(401, { code: "UNAUTHORIZED" }));
    vi.stubGlobal("fetch", fetchMock);
    renderApp();

    await user.type(screen.getByLabelText("API Key"), TEST_API_KEY);
    await user.click(screen.getByRole("button", { name: "Connect" }));
    expect(await screen.findByRole("heading", { name: "Jobs" })).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Status"), "FAILED");
    expect(await screen.findByRole("button", { name: "Connect" })).toBeInTheDocument();
  });
});
