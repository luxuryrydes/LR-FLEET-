import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, download, type Page, type Row } from "../lib/api";
import { can, useAuth } from "../lib/auth";
import { date, dateTime, label, number } from "../lib/format";
import { ImportPreview } from "../components/ImportPreview";
import { Badge, Card, ErrorBox, Field, Notice, PageHeader, Spinner, Tabs } from "../components/ui";

type ImportType = "GPS_KM" | "FUEL";
const FIELD_LABELS: Record<string, string> = {
  vehicle: "Vehicle number / GPS device name", date: "Date", km: "Distance (km)", tripStart: "Trip start time", time: "Time",
  energyType: "Fuel type", quantity: "Quantity", unitPrice: "Rate / price per unit", grossAmount: "Value (before discount)",
  discountAmount: "Discount", taxAmount: "Tax", invoiceAmount: "Invoice amount / cost", odometerKm: "Odometer",
  isFullTank: "Full tank (Y/N)", stationName: "Station / supplier", invoiceNumber: "Invoice no.", transactionRef: "Transaction no.",
  paymentMethod: "Payment method", notes: "Notes",
};
const REQUIRED: Record<ImportType, string[]> = { GPS_KM: ["vehicle", "date", "km"], FUEL: ["vehicle", "date", "energyType", "quantity"] };
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
      {b?.status === "FAILED" && <FailedPanel b={b} onRetry={() => batch.refetch()} />}
      {b?.status === "HEADERS_READY" && <MappingStep b={b} onDone={() => batch.refetch()} />}
      {b?.status === "PARSED" && <PreviewStep b={b} onDone={() => batch.refetch()} />}
      {b && ["COMMITTED", "CANCELLED"].includes(b.status) && <SummaryStep b={b} />}
    </>
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
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (sheet) {
      setHeaderRow(sheet.headerRow);
      setMapping(sheet.suggestedMapping?.[type] ?? {});
    }
  }, [sheetName]); // eslint-disable-line react-hooks/exhaustive-deps

  const missing = REQUIRED[type].filter((f) => !mapping[f]);
  const needsAmount = type === "FUEL" && !mapping.invoiceAmount && !mapping.grossAmount && !mapping.unitPrice;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const clean = Object.fromEntries(Object.entries(mapping).filter(([, v]) => v));
      await api.post(`/api/imports/${b.id}/mapping`, { sheet: sheet.name, headerRow, mapping: clean, options: type === "GPS_KM" ? { recordMode: mode } : {} });
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
        <button className="btn btn-primary" disabled={missing.length > 0 || needsAmount || busy} onClick={submit}>{busy ? "Submitting…" : "Validate rows"}</button>
      </div>
    </Card>
  );
}

type View = "" | "VALID" | "WARNING" | "DUPLICATE" | "REJECTED";

function PreviewStep({ b, onDone }: { b: Row; onDone: () => void }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [view, setView] = useState<View>("");
  const [page, setPage] = useState(0);
  const [policy, setPolicy] = useState<"SKIP" | "REPLACE">("SKIP");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const q = { status: view, page, size: 100 };
  const rows = useQuery({ queryKey: ["import-rows", b.id, q], queryFn: () => api.get<Page<Row>>(`/api/imports/${b.id}/rows`, q) });
  const review = useQuery({ queryKey: ["import-review", b.id], queryFn: () => api.get<Row>(`/api/imports/${b.id}/review`), enabled: b.importType === "GPS_KM" });
  const importable = Number(b.validRows) + Number(b.warningRows);

  const commit = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/imports/${b.id}/commit`, { policy, reason: policy === "REPLACE" ? reason : null });
      qc.invalidateQueries({ queryKey: ["gps"] });
      qc.invalidateQueries({ queryKey: ["fuel"] });
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
    { key: "DUPLICATE" as View, label: "Duplicates in file", count: b.duplicateRows },
    { key: "REJECTED" as View, label: "Rejected", count: b.rejectedRows },
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
        <div className="form-grid">
          <Field label="When a record already exists with a different value">
            <select value={policy} onChange={(e) => setPolicy(e.target.value as "SKIP" | "REPLACE")} aria-label="Duplicate policy">
              <option value="SKIP">Keep existing record (skip)</option>
              {can(user, "manage") && b.importType === "GPS_KM" && <option value="REPLACE">Replace existing record (keeps history)</option>}
            </select>
          </Field>
          {policy === "REPLACE" && (
            <Field label="Reason for replacement" required error={!reason.trim() ? "Required for an audited correction" : undefined}>
              <input value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
          )}
        </div>
        <div className="form-actions">
          <button className="btn" onClick={() => api.post(`/api/imports/${b.id}/cancel`).then(onDone)}>Cancel import</button>
          <button className="btn btn-primary" disabled={busy || importable + Number(b.duplicateRows) === 0 || (policy === "REPLACE" && !reason.trim())} onClick={commit}>
            {busy ? "Importing…" : "Confirm import"}
          </button>
        </div>
      </Card>
    </>
  );
}

function SummaryStep({ b }: { b: Row }) {
  return (
    <Card title={b.status === "COMMITTED" ? "5. Import complete" : "Import cancelled"}>
      <div className="counts">
        {[["Rows in file", b.totalRows], ["Inserted", b.insertedRows], ["Replaced", b.replacedRows], ["Skipped", b.skippedRows], ["Rejected", b.rejectedRows]]
          .map(([l, v]) => <div className="count-pill" key={l as string}><b>{number(v as number, 0)}</b>{l}</div>)}
      </div>
      <dl className="kv">
        <dt>Status</dt><dd><Badge value={b.status} /></dd>
        <dt>Duplicate policy</dt><dd>{label(b.duplicatePolicy)}{b.replaceReason && ` — ${b.replaceReason}`}</dd>
        <dt>Committed</dt><dd>{dateTime(b.committedAt)} {b.committedByName && `by ${b.committedByName}`}</dd>
        <dt>File fingerprint</dt><dd className="mono small">{b.fileSha256}</dd>
      </dl>
      <div className="form-actions">
        <button className="btn" onClick={() => download(`/api/imports/${b.id}/errors`, { format: "xlsx" })}>Download error report (Excel)</button>
        <button className="btn" onClick={() => download(`/api/imports/${b.id}/errors`, { format: "csv" })}>Download error report (CSV)</button>
      </div>
    </Card>
  );
}
