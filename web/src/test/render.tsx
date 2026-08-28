import { render, type RenderOptions } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement, ReactNode } from "react";
import { AuthProvider } from "../auth";

/** Clearly test-only. Never a production key. */
export const TEST_API_KEY = "test-api-key";

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function emptyJobList(page = 0, size = 20) {
  return {
    items: [],
    page,
    size,
    totalElements: 0,
    totalPages: 0,
  };
}

export function renderConsole(
  ui: ReactElement,
  options?: {
    path?: string;
    apiKey?: string | null;
    renderOptions?: Omit<RenderOptions, "wrapper">;
  }
) {
  const path = options?.path ?? "/";
  const apiKey = options?.apiKey === undefined ? TEST_API_KEY : options.apiKey;
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider initialApiKey={apiKey}>{children}</AuthProvider>
      </MemoryRouter>
    );
  }
  return render(ui, { wrapper: Wrapper, ...options?.renderOptions });
}
