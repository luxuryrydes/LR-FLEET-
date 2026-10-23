import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, download, type Row } from "../lib/api";
import { Can, can, useAuth } from "../lib/auth";
import { filtersToQuery } from "../lib/filters";
import { useLookups } from "../lib/hooks";
import { date, number, rupees, todayIso } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { FilterBar } from "../components/FilterBar";
import { Badge, ErrorBox, Field, Modal, PageHeader, Spinner } from "../components/ui";

type View = "" | "OVERDUE" | "DUE_SOON" | "OK" | "MISSING_SERVICE_RECORD" | "NO_DATE" | "NO_BASELINE" | "NO_SCAN";

const STATUS_TEXT: Record<string, string> = {
  OVERDUE: "Overdue", DUE_SOON: "Due soon", OK: "OK", MISSING_SERVICE_RECORD: "No service record",
  MISSING_BASELINE: "No odometer", UNRELIABLE_BASELINE: "Check odometer",
};
const PAGE = 100;

function matches(r: Row, v: View) {
  switch (v) {
    case "": return true;
    case "NO_DATE": return r.lastServiceId && !r.serviceDateRecorded;
    case "NO_BASELINE": return r.status === "MISSING_BASELINE" || r.status === "UNRELIABLE_BASELINE";
    case "NO_SCAN": return r.lastServiceId && !r.lastServiceFileId;
    default: return r.status === v;
  }
}

async function uploadScan(file: File): Promise<number> {
  const form = new FormData();
  form.append("file", file);
  form.append("purpose", "SERVICE_INVOICE");
  return (await api.upload<{ id: number }>("/api/files", form)).id;
}

/** Service portal: every vehicle's last service, next service by km and date, and the job card / invoice scans. */
export function ServiceDuePage() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const { filters, setFilters, extra, update } = useUrlFilters(["due"]);
  const [err, setErr] = useState<unknown>(null);
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<Row | null>(null);
  const [dropping, setDropping] = useState<number | null>(null);
  const base = filtersToQuery({ ...filters, from: "", to: "" });
  const all = useQuery({ queryKey: ["service-status", base], queryFn: () => api.get<Row[]>("/api/service-status", base) });
  const view = extra.due as View;
  const data = all.data ?? [];
  const rows = useMemo(() => data.filter((r) => matches(r, view)), [data, view]);
  const shown = rows.slice(page * PAGE, page * PAGE + PAGE);
  const count = (v: View) => data.filter((r) => matches(r, v)).length;
  const withService = data.filter((r) => r.lastServiceId).length;
  const writer = can(user, "write");
  const pick = (v: View) => { update({ due: view === v ? "" : v }); setPage(0); };
  const refresh = () => qc.invalidateQueries({ queryKey: ["service-status"] });

  const drop = async (r: Row, file: File) => {
    setDropping(r.vehicleId);
    setErr(null);
    try {
      const fileId = await uploadScan(file);
      if (r.lastServiceId) {
        await api.post(`/api/maintenance/${r.lastServiceId}/file`, { fileId });
        refresh();
      } else {
        setOpen({ ...r, droppedFileId: fileId, droppedFileName: file.name });
      }
    } catch (e) {
      setErr(e);
    } finally {
      setDropping(null);
    }
  };

  const card = (v: View, label: string, tone: string, sub?: string) => (
    <button className={`kpi ${tone} ${view === v ? "selected" : ""}`} onClick={() => pick(v)} aria-label={`Show ${label}`}>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{number(count(v), 0)}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </button>
  );

  return (
    <>
      <PageHeader title="Vehicle service"
        subtitle="Last service, next service by km and date, and job card / invoice scans for every vehicle. Current odometer = last trusted reading + GPS km after it."
        actions={<>
          <Can perm="write"><Link className="btn" to="/imports/new?type=SERVICE">Import service register (Excel)</Link></Can>
          <button className="btn" onClick={() => download("/api/service-status/export", { ...base, format: "xlsx" }).catch(setErr)}>Export Excel</button>
        </>} />
      <FilterBar value={filters} onApply={(f) => { setFilters(f); setPage(0); }} hideDates show={["fuelType", "category", "operatorType", "departmentId", "status"]} />
      <ErrorBox error={all.error ?? err} />
      {all.isLoading && <Spinner />}
      {all.data && (
        <>
          <div className="kpis doc-kpis">
            {card("OVERDUE", "Overdue", count("OVERDUE") ? "bad" : "good", "past the km or date")}
            {card("DUE_SOON", "Due soon", count("DUE_SOON") ? "warn" : "good", "within 500 km or 15 days")}
            {card("OK", "Service OK", "good")}
            {card("MISSING_SERVICE_RECORD", "No service record", count("MISSING_SERVICE_RECORD") ? "warn" : "good", "record the first service")}
            {card("NO_DATE", "Date not recorded", count("NO_DATE") ? "warn" : "good", "km reminder only")}
            {card("NO_BASELINE", "No odometer", count("NO_BASELINE") ? "warn" : "good", "no trusted reading")}
            <button className={`kpi ${view === "NO_SCAN" ? "selected" : ""}`} onClick={() => pick("NO_SCAN")} aria-label="Show services without a scan">
              <div className="kpi-label">Job card / invoice</div>
              <div className="kpi-value">{number(withService - count("NO_SCAN"), 0)} <span className="kpi-unit">/ {number(withService, 0)}</span></div>
              <div className="kpi-sub">{count("NO_SCAN")} last services without a scan</div>
            </button>
          </div>
          <div className="table-wrap">
            <div className="table-scroll">
              <table className="table svc-table" aria-label="Vehicle service">
                <thead>
                  <tr>
                    <th className="sticky-col">Vehicle</th><th>Status</th><th>Last service</th>
                    <th style={{ textAlign: "right" }}>Current est. km</th><th>Next service (km)</th>
                    <th>Next service date</th><th>Job card / invoice</th><th />
                  </tr>
                </thead>
                <tbody>
                  {shown.map((r) => {
                    const used = r.intervalKm && r.remainingKm !== null && r.remainingKm !== undefined
                      ? Math.min(100, Math.max(0, ((Number(r.intervalKm) - Number(r.remainingKm)) / Number(r.intervalKm)) * 100)) : null;
                    return (
                      <tr key={r.vehicleId} className={`svc-${r.status}`}>
                        <td className="sticky-col">
                          <Link className="mono strong" to={`/vehicles/${r.vehicleId}`}>{r.registrationNumber}</Link>
                          <div className="muted small">{r.model}{r.departmentName ? ` · ${r.departmentName}` : ""}</div>
                        </td>
                        <td><Badge value={r.status === "MISSING_SERVICE_RECORD" ? "MISSING" : r.status} text={STATUS_TEXT[r.status] ?? r.status} /></td>
                        <td className="nowrap">
                          {r.lastServiceId ? <>
                            <div>{r.serviceDateRecorded ? date(r.lastServiceDate) : <span className="t-amber">date not recorded</span>}</div>
                            <div className="muted small">
                              {[r.lastServiceOdometer !== null && r.lastServiceOdometer !== undefined ? `${number(r.lastServiceOdometer, 0)} km` : null,
                                r.daysSinceService !== null && r.daysSinceService !== undefined ? `${r.daysSinceService} days ago` : null].filter(Boolean).join(" · ")}
                            </div>
                          </> : <span className="muted">—</span>}
                        </td>
                        <td className="num" style={{ textAlign: "right" }}>{number(r.currentOdometer, 0)}</td>
                        <td className="svc-km">
                          {r.nextServiceOdometer !== null && r.nextServiceOdometer !== undefined ? <>
                            <div>{number(r.nextServiceOdometer, 0)} km
                              {r.remainingKm !== null && r.remainingKm !== undefined && (
                                <span className={Number(r.remainingKm) < 0 ? "t-red" : Number(r.remainingKm) <= 500 ? "t-amber" : "muted"}>
                                  {" "}· {Number(r.remainingKm) < 0 ? `${number(-Number(r.remainingKm), 0)} km over` : `${number(r.remainingKm, 0)} km left`}
                                </span>)}
                            </div>
                            {used !== null && <div className="svc-bar" aria-hidden="true"><span style={{ width: `${used}%` }} /></div>}
                          </> : "—"}
                        </td>
                        <td>{r.nextServiceDate ? <>{date(r.nextServiceDate)}
                          <div className={`small ${Number(r.daysRemaining) < 0 ? "t-red" : Number(r.daysRemaining) <= 15 ? "t-amber" : "muted"}`}>
                            {Number(r.daysRemaining) < 0 ? `${-Number(r.daysRemaining)} days late` : `in ${r.daysRemaining} days`}</div></> : "—"}</td>
                        <td className="svc-scan"
                            onDragOver={(e) => { if (writer) e.preventDefault(); }}
                            onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files?.[0]; if (writer && f) drop(r, f); }}>
                          {dropping === r.vehicleId ? "Uploading…" : r.lastServiceFileId
                            ? <button className="link-btn" onClick={() => download(`/api/files/${r.lastServiceFileId}`).catch(setErr)} title={r.lastServiceFileName}>📎 {r.lastServiceFileName}</button>
                            : r.lastServiceId ? <span className="doc-nofile" title="Drop a PDF / photo here">⤒ not uploaded</span> : <span className="muted">—</span>}
                        </td>
                        <td><button className="btn btn-small" onClick={() => setOpen(r)} aria-label={`Open service for ${r.registrationNumber}`}>{writer ? "Record / upload" : "View"}</button></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {rows.length === 0 && <div className="empty">No vehicles in this view.</div>}
            </div>
            {rows.length > PAGE && (
              <div className="pager">
                <span className="muted">{number(rows.length, 0)} vehicles · page {page + 1} of {Math.ceil(rows.length / PAGE)}</span>
                <div className="pager-buttons">
                  <button className="btn btn-small" disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Previous</button>
                  <button className="btn btn-small" disabled={(page + 1) * PAGE >= rows.length} onClick={() => setPage(page + 1)}>Next ›</button>
                </div>
              </div>
            )}
          </div>
          <p className="muted small">
            The bar shows how much of the service interval is used. A service imported without a date keeps the km reminder only.
            {writer && " Drag a job card or invoice (PDF / photo) onto a row's Job card box to attach it to the last service."}
          </p>
        </>
      )}
      {open && <ServiceModal r={open} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); refresh(); }} />}
    </>
  );
}

function ServiceModal({ r, onClose, onSaved }: { r: Row; onClose: () => void; onSaved: () => void }) {
  const { user } = useAuth();
  const { data: lk } = useLookups();
  const qc = useQueryClient();
  const writer = can(user, "write");
  const history = useQuery({ queryKey: ["service-history", r.vehicleId], queryFn: () => api.get<Row[]>(`/api/maintenance/vehicle/${r.vehicleId}`) });
  const [f, setF] = useState({ serviceDate: todayIso(), odometerKm: "", categoryCode: "ROUTINE_SERVICE", description: "Periodic service",
    workshopName: "", totalCost: "", invoiceNumber: "" });
  const [fileId, setFileId] = useState<number | null>((r.droppedFileId as number) ?? null);
  const [fileName, setFileName] = useState<string>((r.droppedFileName as string) ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  const pick = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      setFileId(await uploadScan(file));
      setFileName(file.name);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const attachTo = async (serviceId: number, file: File) => {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/maintenance/${serviceId}/file`, { fileId: await uploadScan(file) });
      qc.invalidateQueries({ queryKey: ["service-history", r.vehicleId] });
      qc.invalidateQueries({ queryKey: ["service-status"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const n = (v: string) => (v === "" ? null : v);
      const res = await api.post<Row>("/api/maintenance", {
        vehicleId: r.vehicleId, serviceDate: f.serviceDate, odometerKm: n(f.odometerKm), categoryCode: f.categoryCode,
        description: f.description.trim() || "Periodic service", workshopName: n(f.workshopName), totalCost: n(f.totalCost),
        invoiceNumber: n(f.invoiceNumber), attachmentFileId: fileId,
      });
      const w = (res.warnings ?? []) as string[];
      if (w.length) {
        setWarnings(w);
        setBusy(false);
        qc.invalidateQueries({ queryKey: ["service-status"] });
        qc.invalidateQueries({ queryKey: ["service-history", r.vehicleId] });
      } else {
        onSaved();
      }
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  const odoLow = f.odometerKm !== "" && r.lastServiceOdometer !== null && Number(f.odometerKm) < Number(r.lastServiceOdometer);
  return (
    <Modal title={`${r.registrationNumber} · service`} onClose={onClose} footer={<>
      <button className="btn" onClick={warnings.length ? onSaved : onClose}>Close</button>
      {writer && !warnings.length && <button className="btn btn-primary" disabled={busy || !f.serviceDate || f.serviceDate > todayIso()} onClick={save}>Save service</button>}
    </>}>
      <ErrorBox error={error} />
      {warnings.length > 0 && <div className="alert alert-warning">Saved. {warnings.join(" ")}</div>}
      <dl className="kv">
        <dt>Status</dt><dd><Badge value={r.status === "MISSING_SERVICE_RECORD" ? "MISSING" : r.status} text={STATUS_TEXT[r.status] ?? r.status} />
          {" "}<span className="muted small">{(r.reasons as string[]).join("; ")}</span></dd>
        <dt>Next service</dt><dd>{r.nextServiceOdometer ? `${number(r.nextServiceOdometer, 0)} km` : "—"}{r.nextServiceDate ? ` or ${date(r.nextServiceDate)}` : ""}
          {r.currentOdometer ? <span className="muted"> · now about {number(r.currentOdometer, 0)} km</span> : null}</dd>
      </dl>
      {writer && !warnings.length && (
        <>
          <h3 className="modal-h">Record a service</h3>
          <div className="form-grid">
            <Field label="Service date" required error={f.serviceDate > todayIso() ? "Cannot be in the future" : undefined}>
              <input type="date" value={f.serviceDate} max={todayIso()} onChange={set("serviceDate")} aria-label="Service date" />
            </Field>
            <Field label="Odometer (km)" hint={r.currentOdometer ? `Estimated now: ${number(r.currentOdometer, 0)} km` : undefined}
                   error={odoLow ? "Lower than the last service odometer" : undefined}>
              <input type="number" min={0} value={f.odometerKm} onChange={set("odometerKm")} aria-label="Service odometer" />
            </Field>
            <Field label="Type of service">
              <select value={f.categoryCode} onChange={set("categoryCode")} aria-label="Type of service">
                {(lk?.serviceCategories ?? []).map((c) => <option key={c.code} value={c.code}>{c.name}{c.resetsServiceInterval ? " (restarts interval)" : ""}</option>)}
              </select>
            </Field>
            <Field label="Work done"><input value={f.description} onChange={set("description")} /></Field>
            <Field label="Workshop / service centre"><input value={f.workshopName} onChange={set("workshopName")} /></Field>
            <Field label="Total cost (₹)"><input type="number" min={0} value={f.totalCost} onChange={set("totalCost")} /></Field>
            <Field label="Invoice / job card no."><input value={f.invoiceNumber} onChange={set("invoiceNumber")} /></Field>
            <Field label="Job card / invoice scan" hint={fileName ? `Ready: ${fileName}` : "PDF, JPG or PNG"}>
              <input type="file" accept=".pdf,.jpg,.jpeg,.png" aria-label="Job card or invoice scan" onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
            </Field>
          </div>
        </>
      )}
      <h3 className="modal-h">Service history</h3>
      {history.isLoading ? <Spinner /> : (
        <table className="table">
          <thead><tr><th>Date</th><th style={{ textAlign: "right" }}>Odometer</th><th>Work</th><th style={{ textAlign: "right" }}>Cost</th><th>Scan</th></tr></thead>
          <tbody>
            {(history.data ?? []).map((h) => (
              <tr key={h.id}>
                <td>{h.serviceDate ? date(h.serviceDate) : <span className="t-amber">not recorded</span>}
                  {h.source === "IMPORT" && <div className="muted small">from Excel register</div>}</td>
                <td className="num" style={{ textAlign: "right" }}>{h.odometerKm !== null ? number(h.odometerKm, 0) : "—"}</td>
                <td>{h.categoryName}<div className="muted small">{h.workshopName ?? ""}</div></td>
                <td className="num" style={{ textAlign: "right" }}>{Number(h.totalCost) ? rupees(h.totalCost) : "—"}</td>
                <td>{h.attachmentFileId
                  ? <button className="link-btn" onClick={() => download(`/api/files/${h.attachmentFileId}`).catch(setError)}>📎 {h.attachmentName ?? "Download"}</button>
                  : writer ? <label className="link-btn upload-link">⤒ Upload<input type="file" accept=".pdf,.jpg,.jpeg,.png" hidden aria-label={`Upload scan for service ${h.id}`}
                      onChange={(e) => e.target.files?.[0] && attachTo(h.id, e.target.files[0])} /></label> : "—"}</td>
              </tr>
            ))}
            {(history.data ?? []).length === 0 && <tr><td colSpan={5} className="muted">No service recorded yet.</td></tr>}
          </tbody>
        </table>
      )}
    </Modal>
  );
}
