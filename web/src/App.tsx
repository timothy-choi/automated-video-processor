import { Link, Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth";
import { ConnectPage } from "./pages/ConnectPage";
import { JobDetailPage } from "./pages/JobDetailPage";
import { JobsPage } from "./pages/JobsPage";

export function App() {
  const { apiKey, disconnect } = useAuth();

  if (!apiKey) {
    return <ConnectPage />;
  }

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="app-header">
        <Link to="/">Media Processing Platform</Link>
        <nav>
          <button className="btn btn-ghost" type="button" onClick={disconnect}>
            Disconnect
          </button>
        </nav>
      </header>
      <Routes>
        <Route path="/" element={<JobsPage />} />
        <Route path="/job/:jobId" element={<JobDetailPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}
