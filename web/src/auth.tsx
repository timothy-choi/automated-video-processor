import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import { createApiClient, type ApiClient } from "./api";

interface AuthContextValue {
  apiKey: string | null;
  api: ApiClient;
  connect: (apiKey: string) => Promise<void>;
  disconnect: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  children,
  initialApiKey = null,
}: {
  children: ReactNode;
  initialApiKey?: string | null;
}) {
  const [apiKey, setApiKey] = useState<string | null>(initialApiKey);
  const keyRef = useRef<string | null>(initialApiKey);

  const disconnect = useCallback(() => {
    keyRef.current = null;
    setApiKey(null);
  }, []);

  const api = useMemo(
    () =>
      createApiClient({
        getApiKey: () => keyRef.current,
        onUnauthorized: () => {
          keyRef.current = null;
          setApiKey(null);
        },
      }),
    []
  );

  const connect = useCallback(
    async (rawKey: string) => {
      const trimmed = rawKey.trim();
      if (!trimmed) {
        throw new Error("Enter an API key.");
      }
      keyRef.current = trimmed;
      try {
        await api.listJobs({ page: 0, size: 1 });
        setApiKey(trimmed);
      } catch (error) {
        keyRef.current = null;
        setApiKey(null);
        throw error;
      }
    },
    [api]
  );

  const value = useMemo(
    () => ({ apiKey, api, connect, disconnect }),
    [apiKey, api, connect, disconnect]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return context;
}
