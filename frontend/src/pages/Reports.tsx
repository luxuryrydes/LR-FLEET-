import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api, download, type Row } from "../lib/api";
import { filtersToQuery } from "../lib/filters";
import { date, label, number, rupees } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { FilterBar } from "../components/FilterBar";
import { Card, ErrorBox, Notice, PageHeader, Spinner } from "../components/ui";

interface Col {
  header: string;
  key: string;
  type: "TEXT" | "INTEGER" | "DECIMAL" | "MONEY" | "DATE" | "DATETIME";
}

function cell(c: Col, v: unknown) {
  if (v === null || v === undefined || v === "") return "—";
  switch (c.type) {
    case "MONEY": return rupees(v as number);
    case "DECIMAL": return number(v as number, 3);
    case "INTEGER": return number(v as number, 0);
    case "DATE": return date(v as string);
    default: return typeof v === "boolean" ? (v ? "Yes" : "No") : /^[A-Z_]+$/.test(String(v)) ? label(String(v)) : String(v);
  }
}

export function ReportsPage() {
  const { filters, setFilters, extra, update } = useUrlFilters(["report"]);
  const catalog = useQuery({ queryKey: ["report-catalog"], queryFn: () => api.get<Row[]>("/api/reports") });
  const key = extra.report || "";
  const q = filtersToQuery(filters);
  const preview = useQuery({ queryKey: ["report", key, q], queryFn: () => api.get<Row>(`/api/reports/${key}`, q), enabled: Boolean(key) });
  const [error, setError] = useState<unknown>(null);
  const [job, setJob] = useState<Row | null>(null);

  useEffect(() => {
    if (!job || ["SUCCEEDED", "FAILED"].includes(job.status)) return;
    const t = setInterval(async () => setJob(await api.get<Row>(`/api/jobs/${job.id}`)), 2000);
    return () => clearInterval(t);
  }, [job]);

  const exportAs = (format: string) => download(`/api/reports/${key}/export`, { ...q, format }).catch(setError);
  const workbook = async () => {
    setError(null);
    try {
      const r = await api.post<{ jobId: number }>("/api/reports/reconciliation-workbook", undefined, q);
      setJob({ id: r.jobId, status: "QUEUED" });
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <PageHeader title="Reports & exports" subtitle="Every report uses the same filters and calculations as the dashboard. Excel exports use ₹ and DD-MM-YYYY." />
      <FilterBar value={filters} onApply={setFilters} />
      <ErrorBox error={error ?? catalog.error ?? preview.error} />
      <div className="grid" style={{ gridTemplateColumns: "minmax(220px, 300px) minmax(0, 1fr)" }}>
        <Card title="Reports">
          <ol style={{ paddingLeft: 18, margin: 0, lineHeight: 1.9 }}>
            {(catalog.data ?? []).map((r) => (
              <li key={r.key}>
                <button className="link-btn" style={{ fontWeight: r.key === key ? 700 : 400, textAlign: "left" }} onClick={() => update({ report: r.key })}>{r.title}</button>
              </li>
            ))}
          </ol>
          <hr style={{ border: "none", borderTop: "1px solid var(--border)", margin: "14px 0" }} />
          <p className="small muted">GPS-vs-fuel coverage workbook (Python analytics, background job):</p>
          <button className="btn btn-small" onClick={workbook} disabled={job !== null && !["SUCCEEDED", "FAILED"].includes(job.status)}>Build reconciliation workbook</button>
          {job && job.status !== "SUCCEEDED" && job.status !== "FAILED" && <Spinner text="Building…" />}
          {job?.status === "SUCCEEDED" && <p><button className="link-btn" onClick={() => download(`/api/files/${job.result.fileId}`).catch(setError)}>Download workbook</button></p>}
          {job?.status === "FAILED" && <Notice kind="error">{job.lastError}</Notice>}
        </Card>
        <div style={{ minWidth: 0 }}>
          {!key && <Card><p className="muted">Choose a report on the left.</p></Card>}
          {key && preview.isLoading && <Spinner />}
          {preview.data && (
            <Card title={preview.data.title} actions={<>
              <button className="btn btn-small" onClick={() => exportAs("xlsx")}>Download Excel</button>
              <button className="btn btn-small" onClick={() => exportAs("csv")}>Download CSV</button>
            </>}>
              <ul className="muted small" style={{ marginTop: 0 }}>
                {(preview.data.notes as string[]).slice(1, -1).map((n) => <li key={n}>{n}</li>)}
              </ul>
              <p className="small">{number(preview.data.totalRows, 0)} rows{preview.data.totalRows > 500 ? " — showing the first 500; download for all" : ""}.</p>
              <div className="table-scroll">
                <table className="table">
                  <thead><tr>{(preview.data.columns as Col[]).map((c) => <th key={c.key} style={{ textAlign: ["MONEY", "DECIMAL", "INTEGER"].includes(c.type) ? "right" : "left" }}>{c.header}</th>)}</tr></thead>
                  <tbody>
                    {(preview.data.rows as Row[]).map((r, i) => (
                      <tr key={i}>
                        {(preview.data!.columns as Col[]).map((c) => (
                          <td key={c.key} style={{ textAlign: ["MONEY", "DECIMAL", "INTEGER"].includes(c.type) ? "right" : "left" }}>{cell(c, r[c.key])}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
                {(preview.data.rows as Row[]).length === 0 && <div className="empty">No data for these filters.</div>}
              </div>
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
