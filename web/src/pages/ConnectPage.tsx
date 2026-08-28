import { useState, type FormEvent } from "react";
import { ApiClientError } from "../api";
import { useAuth } from "../auth";

export function ConnectPage() {
  const { connect } = useAuth();
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      await connect(apiKey);
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "Could not connect.");
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="page">
      <section className="panel connect">
        <h1>Media Processing Platform</h1>
        <p className="muted">
          Enter an API key created for your account. The key is kept only in this browser tab’s
          memory and is cleared when you disconnect or refresh.
        </p>
        <form onSubmit={(event) => void onSubmit(event)}>
          <label className="field">
            API Key
            <input
              type="password"
              autoComplete="off"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              required
            />
          </label>
          {error ? <p className="banner banner-error" role="alert">{error}</p> : null}
          <button className="btn" type="submit" disabled={pending}>
            {pending ? "Connecting…" : "Connect"}
          </button>
        </form>
      </section>
    </main>
  );
}
