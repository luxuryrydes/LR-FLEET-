import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, download, type Page, type Row } from "../lib/api";
import { can, useAuth } from "../lib/auth";
import { date, dateTime, label, number } from "../lib/format";
import { ImportPreview } from "../components/ImportPreview";
import { Badge, Card, ErrorBox, Field, Notice, PageHeader, Spinner, Tabs } from "../components/ui";

type ImportType = "GPS_KM" | "FUEL" | "VEHICLE_MASTER" | "DOCUMENTS" | "SERVICE";
const FIELD_LABELS: Record<string, string> = {
  vehicle: "Vehicle number / GPS device name", date: "Date", km: "Distance (km)", tripStart: "Trip start time", time: "Time",
  energyType: "Fuel type", quantity: "Quantity", unitPrice: "Rate / price per unit", grossAmount: "Value (before discount)",
  discountAmount: "Discount", taxAmount: "Tax", invoiceAmount: "Invoice amount / cost", odometerKm: "Odometer",
  isFullTank: "Full tank (Y/N)", stationName: "Station / supplier", invoiceNumber: "Invoice no.", transactionRef: "Transaction no.",
  paymentMethod: "Payment method", cardNumber: "Fuel card number", notes: "Notes",
  registration: "Registration no.", model: "Vehicle type / model", fuelType: "Fuel type", modelYear: "Model year",
  operatorLabel: "Vendor / Driver (DRIVER, VENDOR, WORKSHOP…)", driverName: "Driver / vendor name", driverPhone: "Driver / vendor contact",
  department: "Department", registrationClass: "Private / Commercial", make: "Make", category: "Category", chassisNumber: "Chassis no.",
  engineNumber: "Engine no.", gpsDeviceName: "GPS device name", soldDate: "Sold date", soldTo: "Sold to / dealer", remarks: "Remarks",
  insuranceExpiry: "Insurance end date", ownDamageExpiry: "Own damage insurance end date", pucExpiry: "Pollution (PUC) end date",
  fitnessExpiry: "Fitness end date", roadTaxExpiry: "Road tax end date", permitExpiry: "Permit 5 years end date",
  permit1yExpiry: "Permit 1 year end date", registrationDate: "Registration date",
  serviceDate: "Last service date", nextServiceOdometer: "Next service at (km)", nextServiceDate: "Next service date",
  recordStatus: "Service record (RECORDED / MISCELLANEOUS…)", description: "Work done", workshopName: "Workshop / service centre",
  totalCost: "Service cost",
};
const EXPIRY_FIELDS = ["insuranceExpiry", "ownDamageExpiry", "pucExpiry", "fitnessExpiry", "roadTaxExpiry", "permitExpiry", "permit1yExpiry", "registrationDate"];
const REQUIRED: Record<ImportType, string[]> = {
  GPS_KM: ["vehicle", "date", "km"], FUEL: ["vehicle", "date", "energyType", "quantity"], VEHICLE_MASTER: ["registration", "model", "fuelType"],
  DOCUMENTS: ["registration"], SERVICE: ["registration"],
};
const STEP_ORDER = ["Upload", "Map columns", "Preview & validate", "Confirm", "Summary"];

function stepOf(status?: string) {
  switch (status) {
    case undefined: return 0;
    case "HEADERS_PENDING": case "HEADERS_READY": return 1;
    case "PARSE_PENDING": case "PARSED": return 2;
    case "COMMITTING": return 3;
    default: return 4;
  }
}

export function ImportWizardPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [type, setType] = useState<ImportType>((params.get("type") as ImportType) || "GPS_KM");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const batch = useQuery({
    queryKey: ["import", id],
    queryFn: () => api.get<Row>(`/api/imports/${id}`),
    enabled: Boolean(id),
    refetchInterval: (q) => (["HEADERS_PENDING", "PARSE_PENDING", "COMMITTING"].includes((q.state.data as Row | undefined)?.status) ? 1500 : false),
  });
  const b = batch.data;
  const step = stepOf(b?.status);

  const upload = async () => {
    if (!file) return;
    setBusy(true);
    setError(null);
    const form = new FormData();
    form.append("file", file);
    form.append("type", type);
    try {
      const res = await api.upload<Row>("/api/imports", form);
      navigate(`/imports/${res.id}`, { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title={b ? `Import #${b.id}: ${b.originalFilename}` : "New import"}
                  subtitle={b ? `${label(b.importType)} · uploaded ${dateTime(b.createdAt)} by ${b.createdByName ?? "—"}` : "Excel (.xlsx, .xls) or CSV exported from the GPS platform or fuel records"} />
      <div className="steps">
        {STEP_ORDER.map((s, i) => <span key={s} className={`step ${i < step ? "done" : i === step ? "current" : ""}`}>{i + 1}. {s}</span>)}
      </div>
      <ErrorBox error={error ?? batch.error} />
      {!id && (
        <Card title="1. Upload file">
          <div className="form-grid">
            <Field label="Import type" required>
              <select value={type} onChange={(e) => setType(e.target.value as ImportType)} aria-label="Import type">
                <option value="GPS_KM">Daily GPS kilometres</option>
                <option value="FUEL">Fuel / energy transactions</option>
                <option value="VEHICLE_MASTER">Vehicle master (vehicles, drivers, vendors, departments)</option>
                <option value="DOCUMENTS">Vehicle documents register (insurance, pollution, fitness, permit, road tax end dates)</option>
                <option value="SERVICE">Vehicle service register (last service odometer, date, next service)</option>
              </select>
            </Field>
            <Field label="File" required hint="Max 50 MB. The original file is kept in private storage.">
              <input type="file" accept=".xlsx,.xls,.csv" aria-label="Spreadsheet file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </Field>
          </div>
          <div className="form-actions">
            <button className="btn btn-primary" disabled={!file || busy} onClick={upload}>{busy ? "Uploading…" : "Upload & detect columns"}</button>
          </div>
        </Card>
      )}
      {b && (b.sameFileImports as Row[])?.length > 0 && b.status !== "COMMITTED" && (
        <Notice kind="warning">
          This exact file was imported before (batch {(b.sameFileImports as Row[]).map((x) => `#${x.id}`).join(", ")}). Rows already saved will be skipped, so nothing is counted twice.
        </Notice>
      )}
      {b && ["HEADERS_PENDING", "PARSE_PENDING", "COMMITTING"].includes(b.status) && <Spinner text={b.status === "HEADERS_PENDING" ? "Reading the file…" : b.status === "PARSE_PENDING" ? "Validating rows…" : "Saving…"} />}
      {b && ["HEADERS_PENDING", "PARSE_PENDING"].includes(b.status) && b.worker && <WorkerNotice w={b.worker as Row} />}
      {b?.status === "FAILED" && <FailedPanel b={b} onRetry={() => batch.refetch()} />}
      {b?.status === "HEADERS_READY" && <MappingStep b={b} onDone={() => batch.refetch()} />}
      {b?.status === "PARSED" && <PreviewStep b={b} onDone={() => batch.refetch()} />}
      {b && ["COMMITTED", "CANCELLED"].includes(b.status) && <SummaryStep b={b} />}
    </>
  );
}

/** Excel files are read by the separate import worker. Without one the file waits in the queue, so say so instead of spinning. */
export function WorkerNotice({ w }: { w: Row }) {
  if (w.online) {
    return Number(w.oldestQueuedSeconds) > 120
      ? <Notice kind="info">The import worker is busy with {w.queuedJobs} file(s) in the queue. This one will start shortly.</Notice>
      : null;
  }
  return (
    <Notice kind="warning">
      <strong>The import worker is not running</strong>, so this file is waiting in the queue and nothing has gone wrong with it.
      {w.wakeConfigured
        ? " The worker is being woken up (a free server sleeps when idle and takes about a minute to start). This page continues on its own."
        : <>
          {" "}On your PC: keep the <em>LR Fleet - Import worker</em> window from <code>run-local.cmd</code> open (start the launcher again if you closed it).
          {" "}On Render: the free plan in <code>render-free.yaml</code> includes the worker as <em>lr-fleet-worker-free</em>; deploy it and set
          {" "}<code>WORKER_WAKE_URL</code> on the API (see docs/DEPLOYMENT.md).
        </>}
      {w.lastSeen && <> Last seen {dateTime(w.lastSeen)}.</>}
    </Notice>
  );
}

function FailedPanel({ b, onRetry }: { b: Row; onRetry: () => void }) {
  const [error, setError] = useState<unknown>(null);
  const retry = async () => {
    try {
      await api.post(`/api/imports/${b.id}/retry`);
      onRetry();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Card title="Import failed">
      <ErrorBox error={error} />
      <Notice kind="error">{b.errorMessage ?? "Processing failed."}</Notice>
      <p className="muted">Nothing from this file was saved. Fix the file and upload it again, or retry if the problem was temporary.</p>
      <button className="btn btn-primary" onClick={retry}>Retry</button>
    </Card>
  );
}

export function MappingStep({ b, onDone }: { b: Row; onDone: () => void }) {
  const type = b.importType as ImportType;
  const sheets = ((b.sheetInfo?.sheets ?? []) as Row[]).filter((s) => (s.headers as string[]).length > 0);
  const fields: string[] = b.sheetInfo?.targetFields?.[type] ?? [];
  const [sheetName, setSheetName] = useState<string>(sheets[0]?.name ?? "");
  const sheet = sheets.find((s) => s.name === sheetName) ?? sheets[0];
  const [headerRow, setHeaderRow] = useState<number>(sheet?.headerRow ?? 1);
  const [mapping, setMapping] = useState<Record<string, string>>(sheet?.suggestedMapping?.[type] ?? {});
  const [mode, setMode] = useState<"DAILY_TOTAL" | "TRIP">("DAILY_TOTAL");
  // Files such as the IGL smart-card statement have no fuel column: every row is CNG.
  const [defaultEnergy, setDefaultEnergy] = useState("");
  // Documents register: also read the other tabs (monthly to-do lists); the latest end date wins.
  const [allSheets, setAllSheets] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (sheet) {
      setHeaderRow(sheet.headerRow);
      setMapping(sheet.suggestedMapping?.[type] ?? {});
    }
  }, [sheetName]); // eslint-disable-line react-hooks/exhaustive-deps

  const missing = REQUIRED[type].filter((f) => !mapping[f] && !(f === "energyType" && defaultEnergy));
  const needsAmount = type === "FUEL" && !mapping.invoiceAmount && !mapping.grossAmount && !mapping.unitPrice;
  const needsDate = (type === "DOCUMENTS" && EXPIRY_FIELDS.every((f) => !mapping[f])) || (type === "SERVICE" && !mapping.serviceDate && !mapping.odometerKm);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const clean = Object.fromEntries(Object.entries(mapping).filter(([, v]) => v));
      await api.post(`/api/imports/${b.id}/mapping`, { sheet: sheet.name, headerRow, mapping: clean, options: type === "GPS_KM" ? { recordMode: mode } : type === "FUEL" && defaultEnergy ? { defaultEnergyType: defaultEnergy }
        : type === "DOCUMENTS" ? { allSheets } : {} });
      onDone();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!sheet) return <Notice kind="error">No readable worksheet with headers was found.</Notice>;
  return (
    <Card title="2. Choose worksheet and map columns">
      <ErrorBox error={error} />
      <div className="form-grid">
        <Field label="Worksheet">
          <select value={sheet.name} onChange={(e) => setSheetName(e.target.value)} aria-label="Worksheet">
            {sheets.map((s) => <option key={s.name} value={s.name}>{s.name}</option>)}
          </select>
        </Field>
        <Field label="Header row" hint="Detected automatically; change if titles sit above the table">
          <input type="number" min={1} value={headerRow} onChange={(e) => setHeaderRow(Number(e.target.value) || 1)} />
        </Field>
        {type === "FUEL" && (
          <Field label="Fuel for rows without a fuel type" hint="e.g. CNG for the IGL smart-card statement, which has no fuel column">
            <select value={defaultEnergy} onChange={(e) => setDefaultEnergy(e.target.value)} aria-label="Fuel for all rows">
              <option value="">— use the file's fuel column —</option>
              <option value="CNG">CNG</option>
              <option value="PETROL">Petrol</option>
              <option value="DIESEL">Diesel</option>
              <option value="ELECTRICITY">Electricity (kWh)</option>
            </select>
          </Field>
        )}
        {type === "DOCUMENTS" && (
          <Field label="Other worksheets" hint="Tabs such as INSURANCE or PUC to-do lists are matched automatically; for each vehicle the latest end date is used">
            <label className="check"><input type="checkbox" checked={allSheets} onChange={(e) => setAllSheets(e.target.checked)} /> Also read every other tab with a vehicle number and end dates</label>
          </Field>
        )}
        {type === "GPS_KM" && (
          <Field label="What does each row represent?" required>
            <select value={mode} onChange={(e) => setMode(e.target.value as "DAILY_TOTAL" | "TRIP")} aria-label="Record mode">
              <option value="DAILY_TOTAL">One daily total per vehicle (repeated rows are copies, never summed)</option>
              <option value="TRIP">Individual trips (distinct trips are summed per day)</option>
            </select>
          </Field>
        )}
      </div>
      <h3 style={{ fontSize: 14, margin: "16px 0 8px" }}>Column mapping</h3>
      {((sheet.remembered?.[type] ?? []) as string[]).length > 0 && (
        <Notice kind="info">Matched from your last import: {(sheet.remembered[type] as string[]).map((f) => FIELD_LABELS[f] ?? f).join(", ")}. Check them and change any that are wrong.</Notice>
      )}
      <div className="form-grid">
        {fields.map((f) => (
          <Field key={f} label={FIELD_LABELS[f] ?? f} required={REQUIRED[type].includes(f)} error={missing.includes(f) ? "Required" : undefined}>
            <select value={mapping[f] ?? ""} onChange={(e) => setMapping({ ...mapping, [f]: e.target.value })} aria-label={`Column for ${FIELD_LABELS[f] ?? f}`}>
              <option value="">— not in file —</option>
              {(sheet.headers as string[]).map((h) => <option key={h} value={h}>{h}</option>)}
            </select>
          </Field>
        ))}
      </div>
      {needsAmount && <Notice kind="warning">Map at least one amount column: invoice amount/cost, value or rate.</Notice>}
      {needsDate && <Notice kind="warning">{type === "SERVICE" ? "Map the last service date or the service odometer column."
        : "Map at least one end-date column (insurance, pollution, fitness, permit or road tax)."}</Notice>}
      <h3 style={{ fontSize: 14, margin: "16px 0 8px" }}>First rows in the file</h3>
      <div className="table-scroll">
        <table className="table">
          <thead><tr>{(sheet.headers as string[]).map((h) => <th key={h}>{h}</th>)}</tr></thead>
          <tbody>
            {(sheet.sample as Row[]).map((r, i) => (
              <tr key={i}>{(sheet.headers as string[]).map((h) => <td key={h}>{r[h] === null || r[h] === undefined ? "" : String(r[h])}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="form-actions">
        <button className="btn" onClick={() => api.post(`/api/imports/${b.id}/cancel`).then(onDone)}>Cancel import</button>
        <button className="btn btn-primary" disabled={missing.length > 0 || needsAmount || needsDate || busy} onClick={submit}>{busy ? "Submitting…" : "Validate rows"}</button>
      </div>
    </Card>
  );
}

type View = "" | "VALID" | "WARNING" | "DUPLICATE" | "REJECTED" | "EXCLUDED";

function PreviewStep({ b, onDone }: { b: Row; onDone: () => void }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [view, setView] = useState<View>("");
  const [page, setPage] = useState(0);
  // Vehicle master: updating changed vehicles is the normal case, so it is the default for managers.
  const [policy, setPolicy] = useState<"SKIP" | "REPLACE">(b.importType === "VEHICLE_MASTER" && can(user, "manage") ? "REPLACE" : "SKIP");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const q = { status: view, page, size: 100 };
  const rows = useQuery({ queryKey: ["import-rows", b.id, q], queryFn: () => api.get<Page<Row>>(`/api/imports/${b.id}/rows`, q) });
  const review = useQuery({ queryKey: ["import-review", b.id], queryFn: () => api.get<Row>(`/api/imports/${b.id}/review`), enabled: b.importType === "GPS_KM" });
  const masterReview = useQuery({ queryKey: ["master-review", b.id], queryFn: () => api.get<Row>(`/api/imports/${b.id}/master-review`),
    enabled: b.importType === "VEHICLE_MASTER" });
  const reasonNeeded = policy === "REPLACE" && b.importType !== "VEHICLE_MASTER";
  const importable = Number(b.validRows) + Number(b.warningRows);

  const commit = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/imports/${b.id}/commit`, { policy, reason: policy === "REPLACE" ? reason : null });
      qc.invalidateQueries({ queryKey: ["vehicles"] });
      qc.invalidateQueries({ queryKey: ["gps"] });
      qc.invalidateQueries({ queryKey: ["fuel"] });
      qc.invalidateQueries({ queryKey: ["doc-matrix"] });
      qc.invalidateQueries({ queryKey: ["service-status"] });
      onDone();
    } catch (e) {
      setError(e);
      onDone();
    } finally {
      setBusy(false);
    }
  };

  const counts = useMemo(() => [
    { key: "" as View, label: "All", count: b.totalRows },
    { key: "VALID" as View, label: "Valid", count: b.validRows },
    { key: "WARNING" as View, label: "Warnings", count: b.warningRows },
    { key: "DUPLICATE" as View, label: b.importType === "DOCUMENTS" ? "Older dates (not used)" : "Duplicates in file", count: b.duplicateRows },
    { key: "REJECTED" as View, label: "Rejected", count: b.rejectedRows },
    ...(b.importType === "GPS_KM" ? [{ key: "EXCLUDED" as View, label: "Not our driver (skipped)", count: b.excludedRows }] : []),
    ...(b.importType === "DOCUMENTS" ? [{ key: "EXCLUDED" as View, label: "No dates", count: b.excludedRows }] : []),
    ...(b.importType === "SERVICE" ? [{ key: "EXCLUDED" as View, label: "No service on record", count: b.excludedRows }] : []),
  ], [b]);

  return (
    <>
      <Card title="3. Preview & validation results"
            actions={<button className="btn btn-small" onClick={() => download(`/api/imports/${b.id}/errors`, { format: "xlsx" })}>Download rejected rows</button>}>
        <div className="counts">
          {counts.map((c) => <div className="count-pill" key={c.key}><b>{number(c.count, 0)}</b>{c.label}</div>)}
        </div>
        <Tabs<View> value={view} onChange={(v) => { setView(v); setPage(0); }} tabs={counts} />
        {rows.isLoading ? <Spinner /> : <ImportPreview rows={rows.data?.items ?? []} importType={b.importType} />}
        {rows.data && rows.data.total > 100 && (
          <div className="pager">
            <span className="muted">Page {page + 1} of {Math.ceil(rows.data.total / 100)}</span>
            <div className="pager-buttons">
              <button className="btn btn-small" disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Previous</button>
              <button className="btn btn-small" disabled={(page + 1) * 100 >= rows.data.total} onClick={() => setPage(page + 1)}>Next ›</button>
            </div>
          </div>
        )}
      </Card>
      <Card title="4. Confirm import">
        <ErrorBox error={error} />
        {b.importType === "GPS_KM" && review.data && (
          <>
            <ul>
              <li><strong>{number(review.data.newDays, 0)}</strong> new vehicle-days will be added.</li>
              <li><strong>{number(review.data.identicalExistingDays, 0)}</strong> vehicle-days are already saved with the same km and will be skipped.</li>
              <li><strong>{number(review.data.existingConflictDays, 0)}</strong> vehicle-days already have a <em>different</em> km saved.</li>
              {review.data.inFileConflictDays > 0 && <li><strong>{review.data.inFileConflictDays}</strong> vehicle-days have conflicting daily totals in this file and will not be imported.</li>}
            </ul>
            {(review.data.existingConflicts as Row[]).length > 0 && (
              <details style={{ marginBottom: 12 }}>
                <summary>Review existing records that differ</summary>
                <table className="table">
                  <thead><tr><th>Vehicle id</th><th>Date</th><th>Saved km</th><th>New km</th><th>Rows</th></tr></thead>
                  <tbody>
                    {(review.data.existingConflicts as Row[]).map((c, i) => (
                      <tr key={i}><td>{c.vehicleId}</td><td>{date(c.date)}</td><td className="num">{c.existingKm}</td><td className="num">{c.newKm}</td><td>{(c.rows as number[]).join(", ")}</td></tr>
                    ))}
                  </tbody>
                </table>
              </details>
            )}
          </>
        )}
        {b.importType === "FUEL" && <p>{number(importable, 0)} rows will be checked again by the server and saved. Rows identical to saved transactions are skipped.</p>}
        {b.importType === "GPS_KM" && Number(b.excludedRows) > 0 && (
          <Notice kind="info">{number(b.excludedRows, 0)} rows belong to vehicles that are not driven by LR's own drivers (vendor, idle, workshop or govt driver) and will not be imported.</Notice>
        )}
        {b.importType === "VEHICLE_MASTER" && <MasterReview r={masterReview.data} loading={masterReview.isLoading} update={policy === "REPLACE"} />}
        {b.importType === "SERVICE" && (
          <p>{number(importable, 0)} services will be checked by the server and saved as routine services. Each one restarts the service
            interval, and a dated one with an odometer reading becomes the odometer baseline (GPS km after it are added). Services already
            saved (same vehicle, date and odometer) are skipped, so the register can be imported again after every update.</p>
        )}
        {b.importType === "DOCUMENTS" && (
          <p>{number(importable, 0)} vehicles will be checked by the server. A later end date than the one saved is added as a renewal (the old
            document stays in the history); the same date is skipped. Vehicles must already be in the vehicle master.</p>
        )}
        <div className="form-grid">
          <Field label="When a record already exists with a different value">
            <select value={policy} onChange={(e) => setPolicy(e.target.value as "SKIP" | "REPLACE")} aria-label="Duplicate policy">
              <option value="SKIP">{b.importType === "VEHICLE_MASTER" ? "Add new vehicles only (leave changed vehicles as they are)"
                : b.importType === "DOCUMENTS" ? "Add new and later end dates only" : "Keep existing record (skip)"}</option>
              {can(user, "manage") && b.importType !== "FUEL" && b.importType !== "SERVICE" && (
                <option value="REPLACE">{b.importType === "VEHICLE_MASTER" ? "Add new vehicles and update changed ones (recommended)"
                  : b.importType === "DOCUMENTS" ? "Also correct saved dates that are later than the sheet" : "Replace existing record (keeps history)"}</option>
              )}
            </select>
          </Field>
          {policy === "REPLACE" && (
            <Field label={b.importType === "VEHICLE_MASTER" ? "Note for the change history (optional)" : "Reason for replacement"} required={reasonNeeded}
                   error={reasonNeeded && !reason.trim() ? "Required for an audited correction" : undefined}>
              <input value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
          )}
        </div>
        <div className="form-actions">
          <button className="btn" onClick={() => api.post(`/api/imports/${b.id}/cancel`).then(onDone)}>Cancel import</button>
          <button className="btn btn-primary" disabled={busy || importable + Number(b.duplicateRows) === 0 || (reasonNeeded && !reason.trim())} onClick={commit}>
            {busy ? "Importing…" : "Confirm import"}
          </button>
        </div>
      </Card>
    </>
  );
}

/** Vehicle master confirm step: what the import will add and update, before anything is saved. */
export function MasterReview({ r, loading, update }: { r?: Row; loading: boolean; update: boolean }) {
  if (loading) return <Spinner text="Comparing the sheet with the saved vehicles…" />;
  if (!r) return null;
  const updates = (r.updates ?? []) as Row[];
  const notInSheet = (r.notInSheet ?? []) as string[];
  return (
    <div className="master-review">
      <div className="counts">
        <div className="count-pill"><b>{number(r.newVehicles, 0)}</b>new vehicles to add</div>
        <div className="count-pill"><b>{number(r.updatedVehicles, 0)}</b>vehicles with changes</div>
        <div className="count-pill"><b>{number(r.unchangedVehicles, 0)}</b>already up to date</div>
      </div>
      {Number(r.updatedVehicles) > 0 && !update && (
        <Notice kind="warning">{number(r.updatedVehicles, 0)} vehicles have changes in this sheet that will <strong>not</strong> be saved with
          "Add new vehicles only". Choose "Add new vehicles and update changed ones" to apply them.</Notice>
      )}
      {updates.length > 0 && (
        <details open={updates.length <= 30}>
          <summary>{update ? "Will be updated" : "Changed in the sheet"} ({updates.length})</summary>
          <ul className="change-list">
            {updates.map((u) => <li key={u.registration}><strong className="mono">{u.registration}</strong>: {(u.changes as string[]).join(", ")}</li>)}
          </ul>
        </details>
      )}
      {((r.added ?? []) as string[]).length > 0 && (
        <details><summary>New vehicles ({r.newVehicles})</summary><p className="mono small">{(r.added as string[]).join(", ")}</p></details>
      )}
      {notInSheet.length > 0 && (
        <details><summary>In the app but not in this sheet ({notInSheet.length})</summary>
          <p className="muted small">These are not changed. If they left the fleet, mark them sold or delete them under Vehicles.</p>
          <p className="mono small">{notInSheet.join(", ")}</p></details>
      )}
      <p className="muted small">Vehicles marked sold keep their sold status, and deleted vehicles are not brought back.</p>
    </div>
  );
}

function SummaryStep({ b }: { b: Row }) {
  return (
    <Card title={b.status === "COMMITTED" ? "5. Import complete" : "Import cancelled"}>
      <div className="counts">
        {(b.importType === "DOCUMENTS"
          ? [["Rows in file", b.totalRows], ["Documents added", b.insertedRows], ["Dates corrected", b.replacedRows], ["Already up to date", b.skippedRows], ["Rejected rows", b.rejectedRows]]
          : b.importType === "VEHICLE_MASTER"
          ? [["Rows in file", b.totalRows], ["Vehicles added", b.insertedRows], ["Vehicles updated", b.replacedRows], ["Already up to date", b.skippedRows], ["Rejected", b.rejectedRows]]
          : b.importType === "SERVICE"
          ? [["Rows in file", b.totalRows], ["Services added", b.insertedRows], ["Already saved", b.skippedRows], ["No service on record", b.excludedRows], ["Rejected", b.rejectedRows]]
          : [["Rows in file", b.totalRows], ["Inserted", b.insertedRows], ["Replaced", b.replacedRows], ["Skipped", b.skippedRows], ["Rejected", b.rejectedRows]])
          .map(([l, v]) => <div className="count-pill" key={l as string}><b>{number(v as number, 0)}</b>{l}</div>)}
      </div>
      {b.importType === "VEHICLE_MASTER" && Number(b.existingConflictRows) > 0 && (
        <Notice kind="warning">
          {number(b.existingConflictRows, 0)} vehicles have different details in this sheet (driver, department, status…) but were left unchanged
          because "Add new vehicles only" was chosen. Import the sheet again and keep "Add new vehicles and update changed ones".
        </Notice>
      )}
      <dl className="kv">
        <dt>Status</dt><dd><Badge value={b.status} /></dd>
        <dt>Duplicate policy</dt><dd>{label(b.duplicatePolicy)}{b.replaceReason && ` — ${b.replaceReason}`}</dd>
        <dt>Committed</dt><dd>{dateTime(b.committedAt)} {b.committedByName && `by ${b.committedByName}`}</dd>
        <dt>File fingerprint</dt><dd className="mono small">{b.fileSha256}</dd>
      </dl>
      <div className="form-actions">
        {b.importType === "DOCUMENTS" && b.status === "COMMITTED" && <Link className="btn btn-primary" to="/documents">Open documents dashboard</Link>}
        {b.importType === "SERVICE" && b.status === "COMMITTED" && <Link className="btn btn-primary" to="/service-due">Open service dashboard</Link>}
        <button className="btn" onClick={() => download(`/api/imports/${b.id}/errors`, { format: "xlsx" })}>Download error report (Excel)</button>
        <button className="btn" onClick={() => download(`/api/imports/${b.id}/errors`, { format: "csv" })}>Download error report (CSV)</button>
      </div>
    </Card>
  );
}
