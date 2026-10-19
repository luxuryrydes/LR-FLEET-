import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, ApiError, download, type Page, type Row } from "../lib/api";
import { Can, useAuth, can } from "../lib/auth";
import { filtersToQuery } from "../lib/filters";
import { date, dateTime, label, number, todayIso } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { DataTable } from "../components/DataTable";
import { FilterBar } from "../components/FilterBar";
import { VehiclePicker } from "../components/VehiclePicker";
import { Badge, ErrorBox, Field, Modal, PageHeader, Select } from "../components/ui";

export function GpsPage() {
  const { filters, setFilters, page, sort, update, extra } = useUrlFilters(["source", "includeHistory"]);
  const [entry, setEntry] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const navigate = useNavigate();
  const q = filtersToQuery(filters, { source: extra.source, includeHistory: extra.includeHistory, page, size: 50, sort });
  const list = useQuery({ queryKey: ["gps", q], queryFn: () => api.get<Page<Row>>("/api/gps", q) });

  return (
    <>
      <PageHeader
        title="Daily GPS kilometres"
        subtitle="One current figure per vehicle per day. Corrections keep the original as history."
        actions={<>
          <button className="btn" onClick={() => download("/api/gps/export", { ...filtersToQuery(filters, { source: extra.source }), format: "xlsx" }).catch(setErr)}>Export Excel</button>
          <button className="btn" onClick={() => download("/api/gps/export", { ...filtersToQuery(filters, { source: extra.source }), format: "csv" }).catch(setErr)}>Export CSV</button>
          <Can perm="write">
            <Link className="btn" to="/imports/new?type=GPS_KM">Upload GPS file</Link>
            <button className="btn btn-primary" onClick={() => setEntry(true)}>+ Manual km</button>
          </Can>
        </>}
      />
      <FilterBar value={filters} onApply={setFilters} show={["fuelType", "category", "departmentId", "status"]} />
      <div className="actions" style={{ marginBottom: 10 }}>
        <Select value={extra.source} onChange={(v) => update({ source: v })} placeholder="All sources" aria-label="Source"
                options={[{ value: "IMPORT", label: "Imported" }, { value: "MANUAL", label: "Manual" }, { value: "CORRECTION", label: "Corrected" }]}
                style={{ width: 180 }} />
        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
          <input type="checkbox" checked={extra.includeHistory === "true"} onChange={(e) => update({ includeHistory: e.target.checked ? "true" : "" })} />
          Show superseded history
        </label>
      </div>
      <ErrorBox error={list.error ?? err} />
      <DataTable
        rows={list.data?.items} loading={list.isLoading} total={list.data?.total} page={page} size={50} sort={sort}
        onSort={(s) => update({ sort: s })} onPage={(p) => update({ page: p })}
        columns={[
          { key: "kmDate", header: "Date", sortable: true, render: (r) => date(r.kmDate) },
          { key: "registrationNumber", header: "Vehicle", sortable: true, render: (r) => <button className="link-btn mono" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>{r.registrationNumber}</button> },
          { key: "distanceKm", header: "Km", sortable: true, align: "right", render: (r) => number(r.distanceKm, 2) },
          { key: "recordMode", header: "Type", render: (r) => `${label(r.recordMode)}${r.tripCount ? ` (${r.tripCount} trips)` : ""}` },
          { key: "source", header: "Source", render: (r) => <Badge value={r.source} /> },
          { key: "sourceDeviceName", header: "GPS name" },
          { key: "importBatchId", header: "Batch", render: (r) => r.importBatchId ? <Link to={`/imports/${r.importBatchId}`}>#{r.importBatchId}</Link> : "—" },
          { key: "isCurrent", header: "Current", render: (r) => r.isCurrent ? <Badge value="OK" text="current" /> :
              <span title={r.supersedeReason}><Badge value="DUPLICATE" text="superseded" /> <span className="muted small">{r.supersededByName} {dateTime(r.supersededAt)}</span></span> },
        ]}
      />
      {entry && <ManualKmModal onClose={() => setEntry(false)} />}
    </>
  );
}

function ManualKmModal({ onClose }: { onClose: () => void }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [vehicle, setVehicle] = useState<Row | null>(null);
  const [form, setForm] = useState({ date: todayIso(), km: "", notes: "", correctionReason: "" });
  const [error, setError] = useState<unknown>(null);
  const [needsReason, setNeedsReason] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const save = async () => {
    setError(null);
    try {
      const res = await api.post<{ outcome: string }>("/api/gps", { vehicleId: vehicle?.id, ...form, correctionReason: form.correctionReason || null });
      setDone(res.outcome === "REPLACED" ? "Correction saved; the previous figure is kept in history." : res.outcome === "SKIPPED_SAME_VALUE" ? "Same figure already recorded." : "Saved.");
      qc.invalidateQueries({ queryKey: ["gps"] });
    } catch (e) {
      if (e instanceof ApiError && e.code === "GPS_EXISTS") setNeedsReason(true);
      setError(e);
    }
  };
  return (
    <Modal title="Manual daily km" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Close</button>
      <button className="btn btn-primary" disabled={!vehicle || !form.km || Boolean(done)} onClick={save}>Save</button>
    </>}>
      <p className="muted">Use when GPS data is unavailable. The record is marked as manually entered.</p>
      <ErrorBox error={error} />
      {done && <div className="alert alert-success">{done}</div>}
      <div className="form-grid">
        <Field label="Vehicle" required><VehiclePicker value={vehicle} onChange={setVehicle} /></Field>
        <Field label="Date" required><input type="date" max={todayIso()} value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></Field>
        <Field label="Km" required><input inputMode="decimal" value={form.km} onChange={(e) => setForm({ ...form, km: e.target.value })} /></Field>
        <Field label="Notes"><input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></Field>
        {(needsReason || form.correctionReason) && can(user, "manage") && (
          <Field label="Correction reason" required hint="Replaces the existing figure; the original is kept">
            <input value={form.correctionReason} onChange={(e) => setForm({ ...form, correctionReason: e.target.value })} />
          </Field>
        )}
      </div>
    </Modal>
  );
}
