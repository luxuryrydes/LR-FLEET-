import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, type Page, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { dateTime, label } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { Badge, ErrorBox, Notice, PageHeader, Select } from "../components/ui";

const TYPES = ["GPS_NO_FUEL", "FUEL_NO_KM", "HIGH_COST_PER_KM", "FALLING_MILEAGE", "REPEATED_INVOICE", "DUPLICATE_GPS", "INVALID_ODOMETER",
  "NO_GPS_REPORT", "SERVICE_DUE", "SERVICE_OVERDUE", "MISSING_SERVICE_BASELINE", "HIGH_MAINTENANCE_COST", "FUEL_QTY_OUT_OF_RANGE", "DOCUMENT_EXPIRY"];

export function AlertsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const type = params.get("type") ?? "";
  const status = params.get("status") ?? "ACTIVE";
  const severity = params.get("severity") ?? "";
  const page = Number(params.get("page") ?? 0);
  const [runResult, setRunResult] = useState<Record<string, number> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const set = (k: string, v: string) => {
    const p = new URLSearchParams(params);
    if (v) p.set(k, v);
    else p.delete(k);
    if (k !== "page") p.delete("page");
    setParams(p);
  };
  const q = { type, status, severity, page, size: 50 };
  const list = useQuery({ queryKey: ["alerts", q], queryFn: () => api.get<Page<Row>>("/api/alerts", q) });
  const summary = useQuery({ queryKey: ["alert-summary"], queryFn: () => api.get<Row[]>("/api/alerts/summary") });
  const byType = new Map<string, number>();
  for (const s of summary.data ?? []) byType.set(s.alertType, (byType.get(s.alertType) ?? 0) + Number(s.openCount));

  const run = async () => {
    setError(null);
    try {
      setRunResult(await api.post<Record<string, number>>("/api/alerts/run"));
      qc.invalidateQueries({ queryKey: ["alerts"] });
      qc.invalidateQueries({ queryKey: ["alert-summary"] });
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <PageHeader title="Alerts & reconciliation"
        subtitle="Rule-based checks on database records. Each alert shows the rule, threshold, observed value and contributing records."
        actions={<Can perm="manage"><button className="btn btn-primary" onClick={run}>Run checks now</button></Can>} />
      <ErrorBox error={error ?? list.error} />
      {runResult && <Notice kind="success">Checks complete: {Object.entries(runResult).filter(([, v]) => v > 0).map(([k, v]) => `${label(k)} ${v}`).join(", ") || "no issues found"}.</Notice>}
      <div className="kpis">
        {TYPES.filter((t) => byType.get(t)).map((t) => (
          <button key={t} className={`kpi ${type === t ? "warn" : ""}`} onClick={() => set("type", type === t ? "" : t)}>
            <div className="kpi-label">{label(t)}</div>
            <div className="kpi-value">{byType.get(t)}</div>
          </button>
        ))}
      </div>
      <div className="filter-bar">
        <label><span>Type</span>
          <Select value={type} onChange={(v) => set("type", v)} placeholder="All types" options={TYPES.map((t) => ({ value: t, label: label(t) }))} aria-label="Alert type" />
        </label>
        <label><span>Status</span>
          <Select value={status} onChange={(v) => set("status", v)} aria-label="Alert status" options={[
            { value: "ACTIVE", label: "Open / in progress" }, { value: "OPEN", label: "Open" }, { value: "ACKNOWLEDGED", label: "Acknowledged" },
            { value: "IN_PROGRESS", label: "In progress" }, { value: "RESOLVED", label: "Resolved" }, { value: "DISMISSED", label: "Dismissed" },
          ]} />
        </label>
        <label><span>Severity</span>
          <Select value={severity} onChange={(v) => set("severity", v)} placeholder="All" aria-label="Severity"
                  options={["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((s) => ({ value: s, label: label(s) }))} />
        </label>
      </div>
      <DataTable rows={list.data?.items} loading={list.isLoading} total={list.data?.total} page={page} size={50}
        onPage={(p) => set("page", String(p))} onRowClick={(r) => navigate(`/alerts/${r.id}`)}
        columns={[
          { key: "severity", header: "Severity", render: (r) => <Badge value={r.severity} /> },
          { key: "alertType", header: "Type", render: (r) => label(r.alertType) },
          { key: "registrationNumber", header: "Vehicle", render: (r) => <span className="mono">{r.registrationNumber ?? "—"}</span> },
          { key: "title", header: "Alert" },
          { key: "status", header: "Status", render: (r) => <Badge value={r.status} /> },
          { key: "assignedToName", header: "Assigned to" },
          { key: "lastDetectedAt", header: "Last detected", render: (r) => dateTime(r.lastDetectedAt) },
        ]} />
    </>
  );
}
