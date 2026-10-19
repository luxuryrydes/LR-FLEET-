import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, download, type Page, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { filtersToQuery } from "../lib/filters";
import { useLookups } from "../lib/hooks";
import { date, number, rupees, todayIso } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { DataTable } from "../components/DataTable";
import { FilterBar } from "../components/FilterBar";
import { VehiclePicker } from "../components/VehiclePicker";
import { Badge, ErrorBox, Field, Modal, Notice, PageHeader, Select } from "../components/ui";
import { VoidModal } from "./Fuel";

export function MaintenancePage() {
  const { filters, setFilters, page, sort, update, extra } = useUrlFilters(["category"]);
  const { data: lk } = useLookups();
  const [entry, setEntry] = useState(false);
  const [voiding, setVoiding] = useState<Row | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const navigate = useNavigate();
  const base = filtersToQuery(filters, { category: extra.category });
  const q = { ...base, page, size: 50, sort };
  const list = useQuery({ queryKey: ["maintenance", q], queryFn: () => api.get<Page<Row>>("/api/maintenance", q) });
  return (
    <>
      <PageHeader title="Maintenance & service records" subtitle="Full chronological history. Interval-resetting services recalculate the next service threshold."
        actions={<>
          <button className="btn" onClick={() => download("/api/reports/maintenance-history/export", { ...base, format: "xlsx" }).catch(setErr)}>Export Excel</button>
          <Can perm="write"><button className="btn btn-primary" onClick={() => setEntry(true)}>+ Service record</button></Can>
        </>} />
      <FilterBar value={filters} onApply={setFilters} show={["fuelType", "category", "vendorId", "departmentId", "status"]} />
      <div className="actions" style={{ marginBottom: 10 }}>
        <Select value={extra.category} onChange={(v) => update({ category: v })} placeholder="All service categories" aria-label="Service category"
                options={(lk?.serviceCategories ?? []).map((c) => ({ value: c.code, label: c.name }))} style={{ width: 240 }} />
      </div>
      <ErrorBox error={list.error ?? err} />
      <DataTable rows={list.data?.items} loading={list.isLoading} total={list.data?.total} page={page} size={50} sort={sort}
        onSort={(s) => update({ sort: s })} onPage={(p) => update({ page: p })}
        columns={[
          { key: "serviceDate", header: "Date", sortable: true, render: (r) => date(r.serviceDate) },
          { key: "registrationNumber", header: "Vehicle", sortable: true, render: (r) => <button className="link-btn mono" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>{r.registrationNumber}</button> },
          { key: "category", header: "Category", sortable: true, render: (r) => <>{r.categoryName} {r.resetsServiceInterval && <Badge value="OK" text="resets interval" />}</> },
          { key: "odometerKm", header: "Odometer", align: "right", render: (r) => number(r.odometerKm, 1) },
          { key: "description", header: "Work performed" },
          { key: "partsCost", header: "Parts", align: "right", render: (r) => rupees(r.partsCost) },
          { key: "labourCost", header: "Labour", align: "right", render: (r) => rupees(r.labourCost) },
          { key: "totalCost", header: "Total", sortable: true, align: "right", render: (r) => <>{rupees(r.totalCost)} {r.costMismatch && <Badge value="WARNING" text="≠ parts+labour" />}</> },
          { key: "workshopName", header: "Workshop", render: (r) => r.workshopName ?? r.vendorName ?? "—" },
          { key: "next", header: "Next service", render: (r) => r.resetsServiceInterval ? `${number(r.nextServiceOdometer, 0)} km · ${date(r.nextServiceDate)}${r.nextServiceOverridden ? " (manual)" : ""}` : "—" },
          { key: "act", header: "", render: (r) => <Can perm="manage"><button className="link-btn" onClick={() => setVoiding(r)}>Void</button></Can> },
        ]} />
      {entry && <ServiceModal onClose={() => setEntry(false)} />}
      {voiding && <VoidModal path={`/api/maintenance/${voiding.id}/void`} what="service record" onClose={() => setVoiding(null)} invalidate="maintenance" />}
    </>
  );
}

function ServiceModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const { data: lk } = useLookups();
  const [vehicle, setVehicle] = useState<Row | null>(null);
  const [f, setF] = useState({
    serviceDate: todayIso(), odometerKm: "", categoryCode: "ROUTINE_SERVICE", description: "", partsCost: "", labourCost: "", otherCost: "",
    taxAmount: "", totalCost: "", workshopName: "", invoiceNumber: "", jobCardNumber: "", nextServiceDate: "", nextServiceOdometer: "", remarks: "",
    attachmentFileId: "",
  });
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<Row | null>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setF({ ...f, [k]: e.target.value });
  const n = (v: string) => (v.trim() === "" ? null : v.trim());
  const upload = async (file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("purpose", "SERVICE_INVOICE");
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
      const r = await api.post<Row>("/api/maintenance", {
        vehicleId: vehicle?.id, serviceDate: f.serviceDate, odometerKm: n(f.odometerKm), categoryCode: f.categoryCode, description: f.description,
        partsCost: n(f.partsCost), labourCost: n(f.labourCost), otherCost: n(f.otherCost), taxAmount: n(f.taxAmount), totalCost: n(f.totalCost),
        workshopName: n(f.workshopName), invoiceNumber: n(f.invoiceNumber), jobCardNumber: n(f.jobCardNumber), nextServiceDate: n(f.nextServiceDate),
        nextServiceOdometer: n(f.nextServiceOdometer), remarks: n(f.remarks), attachmentFileId: f.attachmentFileId ? Number(f.attachmentFileId) : null,
      });
      setResult(r);
      qc.invalidateQueries({ queryKey: ["maintenance"] });
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Modal title="New service record" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Close</button>
      <button className="btn btn-primary" disabled={!vehicle || !f.description.trim() || Boolean(result)} onClick={save}>Save</button>
    </>}>
      <ErrorBox error={error} />
      {result && (
        <Notice kind={(result.warnings as string[]).length ? "warning" : "success"}>
          Saved. Next service: {result.nextServiceOdometer ? `${number(result.nextServiceOdometer, 0)} km` : "—"} {result.nextServiceDate && `or ${date(result.nextServiceDate)}`}.
          {" "}{(result.warnings as string[]).join(" ")}
        </Notice>
      )}
      <div className="form-grid">
        <Field label="Vehicle" required><VehiclePicker value={vehicle} onChange={setVehicle} /></Field>
        <Field label="Service date" required><input type="date" max={todayIso()} value={f.serviceDate} onChange={set("serviceDate")} /></Field>
        <Field label="Odometer (km)" hint="Needed for the km-based next service"><input inputMode="decimal" value={f.odometerKm} onChange={set("odometerKm")} /></Field>
        <Field label="Category" required>
          <select value={f.categoryCode} onChange={set("categoryCode")} aria-label="Service category">
            {(lk?.serviceCategories ?? []).map((c) => <option key={c.code} value={c.code}>{c.name}{c.resetsServiceInterval ? " (resets interval)" : ""}</option>)}
          </select>
        </Field>
        <Field label="Parts & consumables (₹)"><input inputMode="decimal" value={f.partsCost} onChange={set("partsCost")} /></Field>
        <Field label="Labour (₹)"><input inputMode="decimal" value={f.labourCost} onChange={set("labourCost")} /></Field>
        <Field label="Other (₹)"><input inputMode="decimal" value={f.otherCost} onChange={set("otherCost")} /></Field>
        <Field label="Tax (₹)"><input inputMode="decimal" value={f.taxAmount} onChange={set("taxAmount")} /></Field>
        <Field label="Invoice total (₹)" hint="Blank = parts + labour + other + tax"><input inputMode="decimal" value={f.totalCost} onChange={set("totalCost")} /></Field>
        <Field label="Workshop / supplier"><input value={f.workshopName} onChange={set("workshopName")} /></Field>
        <Field label="Invoice no."><input value={f.invoiceNumber} onChange={set("invoiceNumber")} /></Field>
        <Field label="Job card no."><input value={f.jobCardNumber} onChange={set("jobCardNumber")} /></Field>
        <Field label="Next service odometer" hint="Blank = calculated (odometer + interval)"><input inputMode="decimal" value={f.nextServiceOdometer} onChange={set("nextServiceOdometer")} /></Field>
        <Field label="Next service date" hint="Blank = calculated"><input type="date" value={f.nextServiceDate} onChange={set("nextServiceDate")} /></Field>
        <Field label="Invoice / job card file"><input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} /></Field>
      </div>
      <Field label="Description of work performed" required><textarea value={f.description} onChange={set("description")} /></Field>
      <Field label="Remarks"><input value={f.remarks} onChange={set("remarks")} /></Field>
    </Modal>
  );
}
