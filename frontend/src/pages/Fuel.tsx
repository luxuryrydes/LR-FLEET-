import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, download, type Page, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { filtersToQuery } from "../lib/filters";
import { QUANTITY_UNIT, date, label, number, rupees, todayIso } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { DataTable } from "../components/DataTable";
import { FilterBar } from "../components/FilterBar";
import { VehiclePicker } from "../components/VehiclePicker";
import { Badge, ErrorBox, Field, Modal, Notice, PageHeader, Select } from "../components/ui";

const ALLOWED: Record<string, string[]> = {
  PETROL: ["PETROL"], DIESEL: ["DIESEL"], CNG: ["CNG"], CNG_PETROL: ["CNG", "PETROL"], ELECTRIC: ["ELECTRICITY"],
  HYBRID: ["PETROL"], PLUGIN_HYBRID: ["PETROL", "ELECTRICITY"],
};

export function FuelPage() {
  const { filters, setFilters, page, sort, update, extra } = useUrlFilters(["energyType", "flagged"]);
  const [entry, setEntry] = useState(false);
  const [voiding, setVoiding] = useState<Row | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const navigate = useNavigate();
  const base = filtersToQuery(filters, { energyType: extra.energyType, flagged: extra.flagged });
  const q = { ...base, page, size: 50, sort };
  const list = useQuery({ queryKey: ["fuel", q], queryFn: () => api.get<Page<Row>>("/api/fuel", q) });

  return (
    <>
      <PageHeader
        title="Fuel & energy transactions"
        subtitle="Invoice amounts are authoritative; differences from quantity × rate are flagged, never overwritten."
        actions={<>
          <button className="btn" onClick={() => download("/api/fuel/export", { ...base, format: "xlsx" }).catch(setErr)}>Export Excel</button>
          <button className="btn" onClick={() => download("/api/fuel/export", { ...base, format: "csv" }).catch(setErr)}>Export CSV</button>
          <Can perm="write">
            <Link className="btn" to="/imports/new?type=FUEL">Upload fuel file</Link>
            <button className="btn btn-primary" onClick={() => setEntry(true)}>+ Fuel entry</button>
          </Can>
        </>}
      />
      <FilterBar value={filters} onApply={setFilters} show={["fuelType", "category", "vendorId", "departmentId", "status"]} />
      <div className="actions" style={{ marginBottom: 10 }}>
        <Select value={extra.energyType} onChange={(v) => update({ energyType: v })} placeholder="All energy types" aria-label="Energy"
                options={["PETROL", "DIESEL", "CNG", "ELECTRICITY"].map((e) => ({ value: e, label: label(e) }))} style={{ width: 190 }} />
        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          <input type="checkbox" checked={extra.flagged === "true"} onChange={(e) => update({ flagged: e.target.checked ? "true" : "" })} />
          Only flagged (amount mismatch / quantity limit)
        </label>
      </div>
      <ErrorBox error={list.error ?? err} />
      <DataTable
        rows={list.data?.items} loading={list.isLoading} total={list.data?.total} page={page} size={50} sort={sort}
        onSort={(s) => update({ sort: s })} onPage={(p) => update({ page: p })}
        columns={[
          { key: "txnDate", header: "Date", sortable: true, render: (r) => date(r.txnDate) },
          { key: "registrationNumber", header: "Vehicle", sortable: true, render: (r) => <button className="link-btn mono" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>{r.registrationNumber}</button> },
          { key: "energyType", header: "Energy", sortable: true, render: (r) => label(r.energyType) },
          { key: "quantity", header: "Quantity", sortable: true, align: "right", render: (r) => `${number(r.quantity, 3)} ${QUANTITY_UNIT[r.energyType]}` },
          { key: "unitPrice", header: "Rate", align: "right", render: (r) => r.unitPrice === null ? "—" : `${rupees(r.unitPrice)}${r.unitPriceDerived ? "*" : ""}` },
          { key: "expenseAmount", header: "Expense", sortable: true, align: "right", render: (r) => <span title={`Source: ${label(r.amountSource)}`}>{rupees(r.expenseAmount)}</span> },
          { key: "odometerKm", header: "Odometer", align: "right", render: (r) => <>{number(r.odometerKm, 1)} {r.odometerTrusted === false && <Badge value="REJECTED" text="untrusted" />}</> },
          { key: "invoiceNumber", header: "Invoice / txn", render: (r) => [r.invoiceNumber, r.transactionRef].filter(Boolean).join(" / ") || "—" },
          { key: "stationName", header: "Station" },
          { key: "flags", header: "Flags", render: (r) => <>
            {r.amountMismatch && <span title={`Difference ₹${r.amountDifference}`}><Badge value="WARNING" text="amount differs" /></span>}{" "}
            {r.quantityFlag && <Badge value="HIGH" text={label(r.quantityFlag)} />}{" "}
            {r.isVoid && <Badge value="CANCELLED" text="void" />}
          </> },
          { key: "source", header: "Source", render: (r) => <Badge value={r.source} /> },
          { key: "act", header: "", render: (r) => !r.isVoid && <Can perm="manage"><button className="link-btn" onClick={() => setVoiding(r)}>Void</button></Can> },
        ]}
      />
      <p className="muted small">* rate derived from value ÷ quantity.</p>
      {entry && <FuelEntryModal onClose={() => setEntry(false)} />}
      {voiding && <VoidModal path={`/api/fuel/${voiding.id}/void`} what={`fuel transaction of ${date(voiding.txnDate)}`} onClose={() => setVoiding(null)} invalidate="fuel" />}
    </>
  );
}

export function VoidModal({ path, what, onClose, invalidate }: { path: string; what: string; onClose: () => void; invalidate: string }) {
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<unknown>(null);
  const go = async () => {
    try {
      await api.post(path, { reason });
      qc.invalidateQueries({ queryKey: [invalidate] });
      onClose();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Modal title="Void record" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn btn-danger" disabled={!reason.trim()} onClick={go}>Void</button>
    </>}>
      <ErrorBox error={error} />
      <p>Voiding keeps the {what} for audit but removes it from all calculations. Mileage is recalculated.</p>
      <Field label="Reason" required><input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
    </Modal>
  );
}

function FuelEntryModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [vehicle, setVehicle] = useState<Row | null>(null);
  const [f, setF] = useState({
    txnDate: todayIso(), txnTime: "", energyType: "", quantity: "", unitPrice: "", grossAmount: "", discountAmount: "", taxAmount: "",
    invoiceAmount: "", odometerKm: "", isFullTank: "", stationName: "", invoiceNumber: "", transactionRef: "", paymentMethod: "", notes: "",
    attachmentFileId: "",
  });
  const [preview, setPreview] = useState<Row | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<Row | null>(null);
  const allowed = vehicle ? ALLOWED[vehicle.fuelType] ?? [] : [];
  useEffect(() => {
    if (allowed.length === 1 && f.energyType !== allowed[0]) setF((x) => ({ ...x, energyType: allowed[0] }));
  }, [vehicle]); // eslint-disable-line react-hooks/exhaustive-deps

  const payload = () => {
    const n = (v: string) => (v.trim() === "" ? null : v.trim());
    return {
      vehicleId: vehicle?.id, txnDate: f.txnDate, txnTime: n(f.txnTime), energyType: f.energyType, quantity: n(f.quantity),
      unitPrice: n(f.unitPrice), grossAmount: n(f.grossAmount), discountAmount: n(f.discountAmount), taxAmount: n(f.taxAmount),
      invoiceAmount: n(f.invoiceAmount), odometerKm: n(f.odometerKm), isFullTank: f.isFullTank === "" ? null : f.isFullTank === "true",
      stationName: n(f.stationName), invoiceNumber: n(f.invoiceNumber), transactionRef: n(f.transactionRef), paymentMethod: n(f.paymentMethod),
      notes: n(f.notes), attachmentFileId: f.attachmentFileId ? Number(f.attachmentFileId) : null,
    };
  };

  // Server-side preview of the amount that will be saved (frontend never computes final amounts).
  useEffect(() => {
    if (!f.quantity || !(f.unitPrice || f.grossAmount || f.invoiceAmount)) {
      setPreview(null);
      return;
    }
    const t = setTimeout(() => {
      api.post<Row>("/api/fuel/preview-amount", payload()).then(setPreview).catch(() => setPreview(null));
    }, 350);
    return () => clearTimeout(t);
  }, [f.quantity, f.unitPrice, f.grossAmount, f.discountAmount, f.taxAmount, f.invoiceAmount]); // eslint-disable-line react-hooks/exhaustive-deps

  const upload = async (file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("purpose", "FUEL_RECEIPT");
    try {
      const r = await api.upload<{ id: number }>("/api/files", form);
      setF((x) => ({ ...x, attachmentFileId: String(r.id) }));
    } catch (e) {
      setError(e);
    }
  };

  const save = async () => {
    setError(null);
    try {
      const r = await api.post<Row>("/api/fuel", payload());
      setResult(r);
      qc.invalidateQueries({ queryKey: ["fuel"] });
    } catch (e) {
      setError(e);
    }
  };
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  return (
    <Modal title="Fuel / charging entry" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Close</button>
      <button className="btn btn-primary" disabled={!vehicle || !f.energyType || !f.quantity || Boolean(result)} onClick={save}>Save</button>
    </>}>
      <ErrorBox error={error} />
      {result && (
        <Notice kind={result.warnings?.length ? "warning" : "success"}>
          Saved (id {result.id}). {result.warnings?.join(" ")}
        </Notice>
      )}
      <div className="form-grid">
        <Field label="Vehicle" required><VehiclePicker value={vehicle} onChange={setVehicle} /></Field>
        <Field label="Date" required><input type="date" max={todayIso()} value={f.txnDate} onChange={set("txnDate")} /></Field>
        <Field label="Time"><input type="time" value={f.txnTime} onChange={set("txnTime")} /></Field>
        <Field label="Fuel / energy" required hint={vehicle ? `${label(vehicle.fuelType)} vehicle` : undefined}>
          <select value={f.energyType} onChange={set("energyType")} aria-label="Energy type">
            <option value="">Choose…</option>
            {(allowed.length ? allowed : ["PETROL", "DIESEL", "CNG", "ELECTRICITY"]).map((e) => <option key={e} value={e}>{label(e)}</option>)}
          </select>
        </Field>
        <Field label={`Quantity${f.energyType ? ` (${QUANTITY_UNIT[f.energyType]})` : ""}`} required><input inputMode="decimal" value={f.quantity} onChange={set("quantity")} /></Field>
        <Field label="Rate (₹ per unit)"><input inputMode="decimal" value={f.unitPrice} onChange={set("unitPrice")} /></Field>
        <Field label="Value / gross (₹)"><input inputMode="decimal" value={f.grossAmount} onChange={set("grossAmount")} /></Field>
        <Field label="Discount (₹)"><input inputMode="decimal" value={f.discountAmount} onChange={set("discountAmount")} /></Field>
        <Field label="Tax (₹)"><input inputMode="decimal" value={f.taxAmount} onChange={set("taxAmount")} /></Field>
        <Field label="Invoice amount (₹)" hint="Authoritative when given"><input inputMode="decimal" value={f.invoiceAmount} onChange={set("invoiceAmount")} /></Field>
        <Field label="Odometer (km)"><input inputMode="decimal" value={f.odometerKm} onChange={set("odometerKm")} /></Field>
        <Field label="Full tank?">
          <select value={f.isFullTank} onChange={set("isFullTank")} aria-label="Full tank">
            <option value="">Not recorded</option><option value="true">Yes</option><option value="false">No (partial)</option>
          </select>
        </Field>
        <Field label="Station / supplier"><input value={f.stationName} onChange={set("stationName")} /></Field>
        <Field label="Invoice no."><input value={f.invoiceNumber} onChange={set("invoiceNumber")} /></Field>
        <Field label="Transaction no."><input value={f.transactionRef} onChange={set("transactionRef")} /></Field>
        <Field label="Payment method">
          <select value={f.paymentMethod} onChange={set("paymentMethod")} aria-label="Payment method">
            <option value="">—</option>
            {["CASH", "CARD", "UPI", "FUEL_CARD", "CREDIT", "BANK_TRANSFER", "OTHER"].map((p) => <option key={p} value={p}>{label(p)}</option>)}
          </select>
        </Field>
        <Field label="Receipt" hint={f.attachmentFileId ? "Attached" : "PDF or image"}>
          <input type="file" accept=".pdf,.jpg,.jpeg,.png,.webp" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
        </Field>
        <Field label="Notes"><input value={f.notes} onChange={set("notes")} /></Field>
      </div>
      {preview && (
        <Notice kind={preview.mismatch ? "warning" : "info"}>
          Amount that will be saved: <strong>{rupees(preview.expenseAmount)}</strong> ({label(preview.source)}).
          {preview.calculatedAmount !== null && <> Quantity × rate = {rupees(preview.calculatedAmount)}.</>}
          {preview.mismatch && <> Difference ₹{preview.difference}: the invoice amount will be kept and the transaction flagged.</>}
        </Notice>
      )}
    </Modal>
  );
}
