import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api, download, type Row } from "../lib/api";
import { Can, can, useAuth } from "../lib/auth";
import { useLookups } from "../lib/hooks";
import { date, label, number } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { VehiclePicker } from "../components/VehiclePicker";
import { Badge, ErrorBox, Field, Modal, PageHeader, Spinner, Tabs, codes } from "../components/ui";

type Bucket = "matrix" | "expired" | "expiring" | "missing" | "pendingVerification" | "driverLicences" | "vehicle";
type State = "" | "ATTENTION" | "EXPIRED" | "DUE_SOON" | "MISSING" | "NO_FILE";

const STATUS_TEXT: Record<string, string> = {
  VALID: "Valid", DUE_SOON: "Due soon", EXPIRED: "Expired", NOT_REQUIRED: "Not required", NO_EXPIRY: "No end date", MISSING: "Missing", OPTIONAL: "",
};
const PAGE = 100;

async function uploadScan(file: File): Promise<number> {
  const form = new FormData();
  form.append("file", file);
  form.append("purpose", "VEHICLE_DOCUMENT");
  const r = await api.upload<{ id: number }>("/api/files", form);
  return r.id;
}

export function DocumentsPage() {
  const [params] = useSearchParams();
  const vehicleId = params.get("vehicleId");
  const [tab, setTab] = useState<Bucket>(vehicleId ? "vehicle" : "matrix");
  const [adding, setAdding] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const c = useQuery({ queryKey: ["compliance"], queryFn: () => api.get<Record<string, Row[]>>("/api/documents/compliance"), enabled: tab !== "matrix" });
  const vdocs = useQuery({ queryKey: ["docs", vehicleId], queryFn: () => api.get<Row[]>("/api/documents", { vehicleId }), enabled: Boolean(vehicleId) });

  const verify = async (id: number, status: "VERIFIED" | "REJECTED") => {
    try {
      await api.post(`/api/documents/${id}/verify`, { status });
      qc.invalidateQueries({ queryKey: ["compliance"] });
      qc.invalidateQueries({ queryKey: ["docs"] });
      qc.invalidateQueries({ queryKey: ["doc-matrix"] });
    } catch (e) {
      setErr(e);
    }
  };
  const docCols = [
    { key: "registrationNumber", header: "Vehicle", render: (r: Row) => r.vehicleId ? <button className="link-btn mono" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>{r.registrationNumber}</button> : r.driverName ?? "—" },
    { key: "docType", header: "Document", render: (r: Row) => label(r.docType) },
    { key: "documentNumber", header: "Number" },
    { key: "expiryDate", header: "Expiry", render: (r: Row) => r.notApplicable ? "Not required" : date(r.expiryDate) },
    { key: "daysToExpiry", header: "Days left", align: "right" as const, render: (r: Row) => r.daysToExpiry === null || r.daysToExpiry === undefined ? "—" :
        <span style={{ color: r.daysToExpiry < 0 ? "var(--red)" : r.daysToExpiry <= 7 ? "var(--amber)" : undefined }}>{r.daysToExpiry}</span> },
    { key: "verificationStatus", header: "Verification", render: (r: Row) => <Badge value={r.verificationStatus} /> },
    { key: "file", header: "Scan", render: (r: Row) => r.fileId ? <button className="link-btn" onClick={() => download(`/api/files/${r.fileId}`).catch(setErr)}>Download</button> : "—" },
    { key: "act", header: "", render: (r: Row) => r.verificationStatus === "PENDING" && r.fileId && (
      <Can perm="manage">
        <button className="link-btn" onClick={() => verify(r.id, "VERIFIED")}>Verify</button> · <button className="link-btn" onClick={() => verify(r.id, "REJECTED")}>Reject</button>
      </Can>
    ) },
  ];
  const d = c.data;
  return (
    <>
      <PageHeader title="Vehicle documents" subtitle="Insurance, pollution, fitness, permits, road tax and registration for every vehicle, with the scans."
        actions={<>
          <Can perm="write"><Link className="btn" to="/imports/new?type=DOCUMENTS">Import documents register (Excel)</Link></Can>
          <Can perm="write"><button className="btn btn-primary" onClick={() => setAdding(true)}>+ Add document</button></Can>
        </>} />
      <Tabs<Bucket> value={tab} onChange={setTab} tabs={[
        { key: "matrix", label: "Dashboard" },
        ...(vehicleId ? [{ key: "vehicle" as Bucket, label: "This vehicle", count: vdocs.data?.length }] : []),
        { key: "expired", label: "Expired", count: d?.expired.length },
        { key: "expiring", label: "Expiring soon", count: d?.expiring.length },
        { key: "missing", label: "Missing", count: d?.missing.length },
        { key: "pendingVerification", label: "Scans to verify", count: d?.pendingVerification.length },
        { key: "driverLicences", label: "Driver licences", count: d?.driverLicences.length },
      ]} />
      <ErrorBox error={c.error ?? err} />
      {tab === "matrix" && <DocumentMatrix />}
      {tab === "vehicle" && <DataTable rows={vdocs.data} loading={vdocs.isLoading} columns={docCols} />}
      {(tab === "expired" || tab === "expiring" || tab === "pendingVerification") && <DataTable rows={d?.[tab]} loading={c.isLoading} columns={docCols} />}
      {tab === "missing" && <DataTable rows={d?.missing} loading={c.isLoading} rowKey={(r) => `${r.vehicleId}-${r.docType}`} columns={[
        { key: "registrationNumber", header: "Vehicle", render: (r) => <button className="link-btn mono" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>{r.registrationNumber}</button> },
        { key: "docType", header: "Missing document", render: (r) => <Badge value="REJECTED" text={label(r.docType)} /> },
      ]} />}
      {tab === "driverLicences" && <DataTable rows={d?.driverLicences} loading={c.isLoading} rowKey={(r) => r.driverId} columns={[
        { key: "fullName", header: "Driver" },
        { key: "licenceNumber", header: "Licence no.", render: (r) => r.licenceNumber ?? <Badge value="REJECTED" text="missing" /> },
        { key: "licenceExpiry", header: "Expiry", render: (r) => date(r.licenceExpiry) },
        { key: "daysToExpiry", header: "Days left", align: "right", render: (r) => r.daysToExpiry ?? "—" },
      ]} />}
      {adding && <DocumentModal onClose={() => setAdding(false)} />}
    </>
  );
}

/** Vehicles x document types. Click a cell to renew, upload a scan or mark not required; drop a PDF/photo on a cell to upload it. */
export function DocumentMatrix() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [q, setQ] = useState("");
  const [search, setSearch] = useState("");
  const [registrationClass, setRegistrationClass] = useState("");
  const [docType, setDocType] = useState("");
  const [state, setState] = useState<State>("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<{ vehicle: Row; type: string } | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [dropping, setDropping] = useState<string | null>(null);
  const params = { q: search, registrationClass, docType, state };
  const m = useQuery({ queryKey: ["doc-matrix", params], queryFn: () => api.get<Row>("/api/documents/matrix", params) });
  const types = (m.data?.types ?? []) as { code: string; label: string }[];
  const rows = (m.data?.rows ?? []) as Row[];
  const summary = (m.data?.summary ?? {}) as Record<string, Record<string, number>>;
  const shown = useMemo(() => rows.slice(page * PAGE, page * PAGE + PAGE), [rows, page]);
  const writer = can(user, "write");

  const focus = (t: string, s: State) => {
    setDocType(t);
    setState(s);
    setPage(0);
  };
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["doc-matrix"] });
    qc.invalidateQueries({ queryKey: ["compliance"] });
    qc.invalidateQueries({ queryKey: ["docs"] });
  };
  const drop = async (vehicle: Row, type: string, file: File) => {
    setDropping(`${vehicle.vehicleId}-${type}`);
    setErr(null);
    try {
      const cell = (vehicle.documents as Row)[type] as Row;
      const fileId = await uploadScan(file);
      if (cell.id) {
        await api.post(`/api/documents/${cell.id}/file`, { fileId });
        refresh();
      } else {
        setOpen({ vehicle: { ...vehicle, droppedFileId: fileId, droppedFileName: file.name }, type });
      }
    } catch (e) {
      setErr(e);
    } finally {
      setDropping(null);
    }
  };

  return (
    <>
      <div className="kpis doc-kpis">
        {types.map((t) => {
          const s = summary[t.code] ?? {};
          const recorded = (s.VALID ?? 0) + (s.DUE_SOON ?? 0) + (s.EXPIRED ?? 0) + (s.NO_EXPIRY ?? 0);
          const bad = (s.EXPIRED ?? 0) + (s.MISSING ?? 0);
          return (
            <button key={t.code} className={`kpi ${bad ? "bad" : (s.DUE_SOON ?? 0) ? "warn" : "good"} ${docType === t.code ? "selected" : ""}`}
                    onClick={() => focus(docType === t.code && state === "ATTENTION" ? "" : t.code, docType === t.code && state === "ATTENTION" ? "" : "ATTENTION")}
                    aria-label={`${t.label}: show vehicles needing attention`}>
              <div className="kpi-label">{t.label}</div>
              <div className="kpi-value">{number(bad + (s.DUE_SOON ?? 0), 0)} <span className="kpi-unit">need action</span></div>
              <div className="kpi-sub">
                <span className="t-red">{s.EXPIRED ?? 0} expired</span> · <span className="t-amber">{s.DUE_SOON ?? 0} due</span> · {s.MISSING ?? 0} missing
              </div>
              <div className="kpi-sub">Scans {s.WITH_FILE ?? 0} / {recorded}{s.NOT_REQUIRED ? ` · ${s.NOT_REQUIRED} not required` : ""}</div>
            </button>
          );
        })}
      </div>
      <div className="filters">
        <form className="filter-search" onSubmit={(e) => { e.preventDefault(); setSearch(q); setPage(0); }}>
          <input placeholder="Vehicle, model or department" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search vehicles" />
          <button className="btn" type="submit">Search</button>
        </form>
        <select value={registrationClass} onChange={(e) => { setRegistrationClass(e.target.value); setPage(0); }} aria-label="Commercial or private">
          <option value="">Commercial + private</option>
          <option value="COMMERCIAL">Commercial</option>
          <option value="PRIVATE">Private</option>
          <option value="UNKNOWN">Not set</option>
        </select>
        <select value={docType} onChange={(e) => { setDocType(e.target.value); setPage(0); }} aria-label="Document">
          <option value="">Any document</option>
          {types.map((t) => <option key={t.code} value={t.code}>{t.label}</option>)}
        </select>
        <select value={state} onChange={(e) => { setState(e.target.value as State); setPage(0); }} aria-label="Document state">
          <option value="">All vehicles</option>
          <option value="ATTENTION">Needs action (expired, due, missing)</option>
          <option value="EXPIRED">Expired</option>
          <option value="DUE_SOON">Due within {m.data?.reminderDays ?? 30} days</option>
          <option value="MISSING">Missing</option>
          <option value="NO_FILE">Scan not uploaded</option>
        </select>
        <button className="btn" onClick={() => download("/api/documents/matrix/export", { ...params, format: "xlsx" }).catch(setErr)}>Export Excel</button>
      </div>
      <ErrorBox error={m.error ?? err} />
      {m.isLoading && <Spinner />}
      {m.data && (
        <div className="table-wrap">
          <div className="table-scroll">
            <table className="table doc-matrix" aria-label="Vehicle documents">
              <thead>
                <tr>
                  <th className="sticky-col">Vehicle</th>
                  {types.map((t) => <th key={t.code} className={docType === t.code ? "col-focus" : undefined}>{t.label}</th>)}
                </tr>
              </thead>
              <tbody>
                {shown.map((v) => (
                  <tr key={v.vehicleId}>
                    <td className="sticky-col">
                      <Link className="mono strong" to={`/vehicles/${v.vehicleId}`}>{v.registrationNumber}</Link>
                      <div className="muted small">{v.model}{v.registrationClass ? ` · ${label(v.registrationClass)}` : ""}{v.departmentName ? ` · ${v.departmentName}` : ""}</div>
                    </td>
                    {types.map((t) => {
                      const cell = (v.documents as Row)[t.code] as Row;
                      const busy = dropping === `${v.vehicleId}-${t.code}`;
                      return (
                        <td key={t.code} className={`doc-cell doc-${cell.status}`}
                            onDragOver={(e) => { if (writer) e.preventDefault(); }}
                            onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files?.[0]; if (writer && f) drop(v, t.code, f); }}>
                          <button className="doc-btn" onClick={() => setOpen({ vehicle: v, type: t.code })}
                                  aria-label={`${v.registrationNumber} ${t.label}: ${STATUS_TEXT[cell.status] || "not recorded"}`}>
                            {busy ? "Uploading…" : <>
                              <span className="doc-date">{cell.notApplicable ? "N/A" : cell.expiryDate ? date(cell.expiryDate) : cell.issueDate ? date(cell.issueDate) : cell.required ? "Missing" : "—"}</span>
                              <span className="doc-meta">
                                {cell.status === "EXPIRED" && `${-Number(cell.daysToExpiry)} d ago`}
                                {cell.status === "DUE_SOON" && `${cell.daysToExpiry} d left`}
                                {cell.id && !cell.notApplicable && <span className={cell.fileId ? "doc-file" : "doc-nofile"} title={cell.fileId ? `Scan: ${cell.fileName}` : "No scan uploaded"}>{cell.fileId ? "📎" : "⤒"}</span>}
                              </span>
                            </>}
                          </button>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length === 0 && <div className="empty">No vehicles match these filters.</div>}
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
      )}
      <p className="muted small">
        Red = expired, amber = due within {m.data?.reminderDays ?? 30} days, green = valid, grey = not required. 📎 = scan uploaded, ⤒ = scan still to upload.
        {writer && " Click a box to renew or upload, or drag a PDF / photo onto a box."} Commercial vehicles also need fitness and both permits.
      </p>
      {open && <CellModal vehicle={open.vehicle} type={open.type} typeLabel={types.find((t) => t.code === open.type)?.label ?? open.type}
                          onClose={() => setOpen(null)} onSaved={() => { setOpen(null); refresh(); }} />}
    </>
  );
}

function CellModal({ vehicle, type, typeLabel, onClose, onSaved }: { vehicle: Row; type: string; typeLabel: string; onClose: () => void; onSaved: () => void }) {
  const { user } = useAuth();
  const cell = (vehicle.documents as Row)[type] as Row;
  const history = useQuery({ queryKey: ["docs", String(vehicle.vehicleId), type], queryFn: () => api.get<Row[]>("/api/documents", { vehicleId: vehicle.vehicleId, docType: type }) });
  const [f, setF] = useState({ expiryDate: "", issueDate: "", documentNumber: "", notApplicable: false, remarks: "" });
  const [fileId, setFileId] = useState<number | null>((vehicle.droppedFileId as number) ?? null);
  const [fileName, setFileName] = useState<string>((vehicle.droppedFileName as string) ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const writer = can(user, "write");
  const isRc = type === "REGISTRATION";

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
  const attach = async () => {
    if (!fileId) return;
    setBusy(true);
    try {
      await api.post(`/api/documents/${cell.id}/file`, { fileId });
      onSaved();
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const n = (v: string) => (v === "" ? null : v);
      await api.post("/api/documents", {
        vehicleId: vehicle.vehicleId, driverId: null, docType: type, documentNumber: n(f.documentNumber), issuer: null,
        issueDate: n(f.issueDate), expiryDate: f.notApplicable ? null : n(f.expiryDate), fileId, remarks: n(f.remarks), notApplicable: f.notApplicable,
      });
      onSaved();
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  const canSave = f.notApplicable || (isRc ? Boolean(f.issueDate || fileId) : Boolean(f.expiryDate));
  return (
    <Modal title={`${vehicle.registrationNumber} · ${typeLabel}`} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Close</button>
      {writer && cell.id && fileId && !f.expiryDate && !f.notApplicable && <button className="btn btn-primary" disabled={busy} onClick={attach}>Attach scan to current document</button>}
      {writer && <button className="btn btn-primary" disabled={busy || !canSave} onClick={save}>{cell.id ? "Save renewal" : "Save document"}</button>}
    </>}>
      <ErrorBox error={error} />
      <dl className="kv">
        <dt>Current</dt>
        <dd>{cell.id ? <>
          <Badge value={cell.status} text={STATUS_TEXT[cell.status] || label(cell.status)} />
          {" "}{cell.notApplicable ? (cell.remarks ?? "Not required") : cell.expiryDate ? `ends ${date(cell.expiryDate)}` : cell.issueDate ? `issued ${date(cell.issueDate)}` : ""}
          {cell.documentNumber && <> · No. {cell.documentNumber}</>}
        </> : cell.required ? <Badge value="HIGH" text="Missing — required" /> : "Not recorded"}</dd>
        <dt>Scan</dt>
        <dd>{cell.fileId
          ? <button className="link-btn" onClick={() => download(`/api/files/${cell.fileId}`).catch(setError)}>{cell.fileName ?? "Download"}</button>
          : cell.id && !cell.notApplicable ? <span className="t-amber">Not uploaded yet</span> : "—"}</dd>
      </dl>
      {writer && (
        <>
          <h3 className="modal-h">{cell.id ? "Upload scan or record the renewal" : "Record this document"}</h3>
          <div className="form-grid">
            <Field label="Scan / PDF / photo" hint={fileName ? `Ready: ${fileName}` : "PDF, JPG or PNG"}>
              <input type="file" accept=".pdf,.jpg,.jpeg,.png" aria-label="Document scan" onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
            </Field>
            {!isRc && (
              <Field label={cell.id ? "New end date (after renewal)" : "End date"} error={f.expiryDate && cell.expiryDate && f.expiryDate <= cell.expiryDate ? "Earlier than or same as the current end date" : undefined}>
                <input type="date" value={f.expiryDate} disabled={f.notApplicable} onChange={(e) => setF({ ...f, expiryDate: e.target.value })} aria-label="End date" />
              </Field>
            )}
            <Field label={isRc ? "Registration date" : "Issue date"}><input type="date" value={f.issueDate} onChange={(e) => setF({ ...f, issueDate: e.target.value })} /></Field>
            <Field label="Document / policy number"><input value={f.documentNumber} onChange={(e) => setF({ ...f, documentNumber: e.target.value })} /></Field>
            <Field label="Remarks"><input value={f.remarks} onChange={(e) => setF({ ...f, remarks: e.target.value })} /></Field>
            {!isRc && (
              <Field label="Not needed for this vehicle">
                <label className="check"><input type="checkbox" checked={f.notApplicable} onChange={(e) => setF({ ...f, notApplicable: e.target.checked })} /> Not required (e.g. EV, lifetime tax)</label>
              </Field>
            )}
          </div>
        </>
      )}
      <h3 className="modal-h">History</h3>
      {history.isLoading ? <Spinner /> : (
        <table className="table">
          <thead><tr><th>End date</th><th>Number</th><th>Source</th><th>Scan</th><th>Verification</th></tr></thead>
          <tbody>
            {(history.data ?? []).map((h) => (
              <tr key={h.id}>
                <td>{h.notApplicable ? "Not required" : date(h.expiryDate ?? h.issueDate)}</td>
                <td>{h.documentNumber ?? "—"}</td>
                <td>{h.source === "IMPORT" ? "Excel register" : "Entered"}</td>
                <td>{h.fileId ? <button className="link-btn" onClick={() => download(`/api/files/${h.fileId}`).catch(setError)}>{h.fileName ?? "Download"}</button> : "—"}</td>
                <td>{h.fileId ? <Badge value={h.verificationStatus} /> : <span className="muted">no scan</span>}</td>
              </tr>
            ))}
            {(history.data ?? []).length === 0 && <tr><td colSpan={5} className="muted">Nothing recorded yet.</td></tr>}
          </tbody>
        </table>
      )}
    </Modal>
  );
}

function DocumentModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const { data: lk } = useLookups();
  const [vehicle, setVehicle] = useState<Row | null>(null);
  const [f, setF] = useState({ docType: "INSURANCE", documentNumber: "", issuer: "", issueDate: "", expiryDate: "", remarks: "", fileId: "", driverId: "" });
  const [error, setError] = useState<unknown>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });
  const upload = async (file: File) => {
    try {
      const id = await uploadScan(file);
      setF((x) => ({ ...x, fileId: String(id) }));
    } catch (e) {
      setError(e);
    }
  };
  const save = async () => {
    setError(null);
    try {
      const n = (v: string) => (v === "" ? null : v);
      await api.post("/api/documents", {
        vehicleId: f.docType === "DRIVING_LICENCE" ? null : vehicle?.id ?? null, driverId: f.docType === "DRIVING_LICENCE" ? Number(f.driverId) || null : null,
        docType: f.docType, documentNumber: n(f.documentNumber), issuer: n(f.issuer), issueDate: n(f.issueDate), expiryDate: n(f.expiryDate),
        fileId: f.fileId ? Number(f.fileId) : null, remarks: n(f.remarks), notApplicable: false,
      });
      qc.invalidateQueries({ queryKey: ["compliance"] });
      qc.invalidateQueries({ queryKey: ["docs"] });
      qc.invalidateQueries({ queryKey: ["doc-matrix"] });
      onClose();
    } catch (e) {
      setError(e);
    }
  };
  const isLicence = f.docType === "DRIVING_LICENCE";
  return (
    <Modal title="Add document" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn btn-primary" disabled={isLicence ? !f.driverId : !vehicle} onClick={save}>Save</button>
    </>}>
      <ErrorBox error={error} />
      <div className="form-grid">
        <Field label="Document type" required>
          <select value={f.docType} onChange={set("docType")} aria-label="Document type">
            {codes(lk?.documentTypes).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </Field>
        {isLicence ? (
          <Field label="Driver" required>
            <select value={f.driverId} onChange={set("driverId")} aria-label="Driver">
              <option value="">Choose…</option>
              {(lk?.drivers ?? []).map((d) => <option key={d.id} value={d.id}>{d.fullName}</option>)}
            </select>
          </Field>
        ) : (
          <Field label="Vehicle" required><VehiclePicker value={vehicle} onChange={setVehicle} /></Field>
        )}
        <Field label="Document number"><input value={f.documentNumber} onChange={set("documentNumber")} /></Field>
        <Field label="Issuer"><input value={f.issuer} onChange={set("issuer")} /></Field>
        <Field label="Issue date"><input type="date" value={f.issueDate} onChange={set("issueDate")} /></Field>
        <Field label="Expiry date" error={f.issueDate && f.expiryDate && f.expiryDate < f.issueDate ? "Expiry must be after issue date" : undefined}>
          <input type="date" value={f.expiryDate} onChange={set("expiryDate")} />
        </Field>
        <Field label="Scan / PDF"><input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} /></Field>
        <Field label="Remarks"><input value={f.remarks} onChange={set("remarks")} /></Field>
      </div>
    </Modal>
  );
}
