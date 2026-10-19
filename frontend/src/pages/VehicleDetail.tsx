import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type Page, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { EFFICIENCY_UNIT, QUANTITY_UNIT, date, dateTime, daysAgoIso, label, number, rupees } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { Badge, Card, ErrorBox, Field, Modal, Notice, PageHeader, Spinner, Tabs } from "../components/ui";

type Tab = "overview" | "gps" | "fuel" | "intervals" | "service" | "documents" | "odometer" | "history" | "alerts";

export function VehicleDetailPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>("overview");
  const [deactivate, setDeactivate] = useState(false);
  const v = useQuery({ queryKey: ["vehicle", id], queryFn: () => api.get<Row>(`/api/vehicles/${id}`) });
  const refresh = () => qc.invalidateQueries({ queryKey: ["vehicle", id] });

  if (v.isLoading) return <Spinner />;
  if (v.error || !v.data) return <ErrorBox error={v.error} />;
  const d = v.data;
  const s = d.serviceStatus as Row;

  return (
    <>
      <PageHeader
        title={d.registrationNumber}
        subtitle={`${d.make} ${d.model}${d.modelYear ? ` · ${d.modelYear}` : ""} · ${label(d.fuelType)} · ${label(d.categoryCode)}`}
        actions={
          <>
            <Badge value={d.status} />
            <Can perm="write"><Link className="btn" to={`/vehicles/${id}/edit`}>Edit</Link></Can>
            <Can perm="manage">
              {d.status !== "INACTIVE" && <button className="btn btn-danger" onClick={() => setDeactivate(true)}>Deactivate</button>}
            </Can>
          </>
        }
      />
      <Tabs<Tab> value={tab} onChange={setTab} tabs={[
        { key: "overview", label: "Overview" }, { key: "gps", label: "GPS km" }, { key: "fuel", label: "Fuel" },
        { key: "intervals", label: "Mileage intervals" }, { key: "service", label: "Service history" }, { key: "documents", label: "Documents" },
        { key: "odometer", label: "Odometer" }, { key: "history", label: "Assignment history" }, { key: "alerts", label: "Alerts" },
      ]} />
      {tab === "overview" && <Overview d={d} s={s} />}
      {tab === "gps" && <VehicleList path="/api/gps" vehicleId={id!} columns={[
        { key: "kmDate", header: "Date", render: (r) => date(r.kmDate) },
        { key: "distanceKm", header: "Km", align: "right", render: (r) => number(r.distanceKm, 2) },
        { key: "recordMode", header: "Type", render: (r) => label(r.recordMode) },
        { key: "source", header: "Source", render: (r) => <Badge value={r.source} /> },
        { key: "sourceDeviceName", header: "GPS name" },
      ]} />}
      {tab === "fuel" && <VehicleList path="/api/fuel" vehicleId={id!} columns={[
        { key: "txnDate", header: "Date", render: (r) => date(r.txnDate) },
        { key: "energyType", header: "Energy", render: (r) => label(r.energyType) },
        { key: "quantity", header: "Qty", align: "right", render: (r) => `${number(r.quantity, 3)} ${QUANTITY_UNIT[r.energyType]}` },
        { key: "expenseAmount", header: "Expense", align: "right", render: (r) => rupees(r.expenseAmount) },
        { key: "odometerKm", header: "Odometer", align: "right", render: (r) => <>{number(r.odometerKm, 1)} {r.odometerTrusted === false && <Badge value="REJECTED" text="untrusted" />}</> },
        { key: "invoiceNumber", header: "Invoice" },
        { key: "flags", header: "Flags", render: (r) => <>{r.amountMismatch && <Badge value="WARNING" text="amount differs" />} {r.quantityFlag && <Badge value="HIGH" text={label(r.quantityFlag)} />}</> },
      ]} />}
      {tab === "intervals" && <Intervals id={id!} />}
      {tab === "service" && <VehicleList path="/api/maintenance" vehicleId={id!} columns={[
        { key: "serviceDate", header: "Date", render: (r) => date(r.serviceDate) },
        { key: "categoryName", header: "Category" },
        { key: "odometerKm", header: "Odometer", align: "right", render: (r) => number(r.odometerKm, 1) },
        { key: "description", header: "Work done" },
        { key: "totalCost", header: "Total", align: "right", render: (r) => rupees(r.totalCost) },
        { key: "nextServiceOdometer", header: "Next service", render: (r) => r.resetsServiceInterval ? `${number(r.nextServiceOdometer, 0)} km / ${date(r.nextServiceDate)}` : "—" },
      ]} />}
      {tab === "documents" && <Documents id={id!} />}
      {tab === "odometer" && <OdometerTab id={id!} onChange={refresh} />}
      {tab === "history" && <History id={id!} />}
      {tab === "alerts" && <AlertsTab id={id!} />}
      {deactivate && <DeactivateModal id={id!} onClose={() => setDeactivate(false)} onDone={refresh} />}
    </>
  );
}

function Overview({ d, s }: { d: Row; s: Row }) {
  return (
    <div className="grid grid-2">
      <Card title="Service status">
        {s && s.status ? (
          <>
            <p><Badge value={s.status} /> {(s.reasons as string[] | undefined)?.join("; ")}</p>
            <dl className="kv">
              <dt>Current est. odometer</dt><dd className="num">{number(s.currentOdometer, 0)} km</dd>
              <dt>Baseline</dt><dd>{s.baselineKm ? `${number(s.baselineKm, 0)} km on ${date(s.baselineDate)} + ${number(s.gpsKmAfterBaseline ?? 0, 0)} GPS km since` : "No trusted odometer"}</dd>
              <dt>Last service</dt><dd>{s.lastServiceDate ? `${date(s.lastServiceDate)} at ${number(s.lastServiceOdometer, 0)} km` : "None recorded"}</dd>
              <dt>Next service</dt><dd>{s.nextServiceOdometer ? `${number(s.nextServiceOdometer, 0)} km` : "—"} {s.nextServiceDate && `or ${date(s.nextServiceDate)}`}</dd>
              <dt>Remaining</dt><dd className="num">{s.remainingKm !== null && s.remainingKm !== undefined ? `${number(s.remainingKm, 0)} km` : "—"}{s.daysRemaining !== null && s.daysRemaining !== undefined && ` · ${s.daysRemaining} days`}</dd>
              <dt>Interval</dt><dd>{number(s.intervalKm, 0)} km / {s.intervalMonths} months</dd>
            </dl>
          </>
        ) : <span className="muted">Not available</span>}
      </Card>
      <Card title="Mileage (last 90 days, weighted)">
        {(d.efficiency as Row[]).length === 0 && <span className="muted">No refuelling intervals yet. Mileage needs two fills of the same fuel with odometer or complete GPS data.</span>}
        {(d.efficiency as Row[]).map((e) => (
          <div key={e.energyType} style={{ marginBottom: 10 }}>
            <strong>{label(e.energyType)}: </strong>
            {e.mileage !== null ? `${number(e.mileage, 2)} ${EFFICIENCY_UNIT[e.energyType]}` : <Badge value={e.status} />}
            <div className="muted small">
              {number(e.eligibleKm, 0)} km ÷ {number(e.eligibleQuantity, 2)} {QUANTITY_UNIT[e.energyType]} over {e.eligibleIntervals} interval(s); {e.excludedIntervals} excluded.
              {e.expectedMileage && ` Expected ${number(e.expectedMileage, 2)} (${label(e.expectedSource)}), variance ${e.variancePct === null ? "—" : `${number(e.variancePct, 2)}%`}.`}
            </div>
          </div>
        ))}
      </Card>
      <Card title="Vehicle details">
        <dl className="kv">
          <dt>Ownership</dt><dd>{label(d.ownershipType)}{d.vendorName && ` · ${d.vendorName}`}</dd>
          <dt>Driver</dt><dd>{d.driverName ?? "Unassigned"} {d.driverPhone && <span className="muted">({d.driverPhone})</span>}</dd>
          <dt>Department</dt><dd>{d.departmentName ?? "—"}</dd>
          <dt>Customer</dt><dd>{d.customerName ?? "—"}</dd>
          <dt>Expected mileage</dt><dd>{number(d.expectedMileage, 2)}</dd>
          <dt>Tank / battery</dt><dd>{number(d.tankCapacity, 1)} L / {number(d.batteryCapacityKwh, 1)} kWh</dd>
          <dt>Purchase</dt><dd>{date(d.purchaseDate)} · {rupees(d.purchaseCost)}</dd>
          <dt>Chassis / engine</dt><dd className="mono">{d.chassisNumber ?? "—"} / {d.engineNumber ?? "—"}</dd>
          <dt>GPS device name</dt><dd>{d.gpsDeviceName ?? "—"}</dd>
          <dt>Remarks</dt><dd>{d.remarks ?? "—"}</dd>
        </dl>
      </Card>
      <Aliases id={String(d.id)} />
    </div>
  );
}

function VehicleList({ path, vehicleId, columns }: { path: string; vehicleId: string; columns: Parameters<typeof DataTable>[0]["columns"] }) {
  const [page, setPage] = useState(0);
  const q = { vehicleId, from: daysAgoIso(3650), page, size: 25 };
  const r = useQuery({ queryKey: [path, q], queryFn: () => api.get<Page<Row>>(path, q) });
  return (
    <>
      <ErrorBox error={r.error} />
      <DataTable columns={columns} rows={r.data?.items} loading={r.isLoading} total={r.data?.total} page={page} onPage={setPage} />
    </>
  );
}

function Intervals({ id }: { id: string }) {
  const qc = useQueryClient();
  const r = useQuery({ queryKey: ["intervals", id], queryFn: () => api.get<Row[]>(`/api/vehicles/${id}/efficiency-intervals`) });
  const recalc = async () => {
    await api.post(`/api/vehicles/${id}/recalculate`);
    qc.invalidateQueries({ queryKey: ["intervals", id] });
    qc.invalidateQueries({ queryKey: ["vehicle", id] });
  };
  return (
    <Card title="Refuelling intervals (Java calculation engine)" actions={<Can perm="write"><button className="btn btn-small" onClick={recalc}>Recalculate</button></Can>}>
      <p className="muted small">Each interval runs from one fill to the next fill of the same fuel. Excluded intervals are shown with the reason and never counted.</p>
      <DataTable rows={r.data} loading={r.isLoading} columns={[
        { key: "energyType", header: "Energy", render: (x) => label(x.energyType) },
        { key: "range", header: "Period", render: (x) => `${date(x.startDate)} → ${date(x.endDate)}` },
        { key: "method", header: "Method", render: (x) => `${label(x.method)} / ${label(x.distanceSource)}` },
        { key: "odo", header: "Odometer", render: (x) => x.startOdometer ? `${number(x.startOdometer, 0)} → ${number(x.endOdometer, 0)}` : "—" },
        { key: "distanceKm", header: "Km", align: "right", render: (x) => number(x.distanceKm, 1) },
        { key: "quantity", header: "Qty", align: "right", render: (x) => `${number(x.quantity, 3)} ${QUANTITY_UNIT[x.energyType]}` },
        { key: "mileage", header: "Mileage", align: "right", render: (x) => x.mileage ? `${number(x.mileage, 2)} ${EFFICIENCY_UNIT[x.energyType]}` : "—" },
        { key: "status", header: "Status", render: (x) => <><Badge value={x.status} /> {x.exclusionReason && <span className="muted small">{label(x.exclusionReason)}</span>}</> },
      ]} />
    </Card>
  );
}

function Documents({ id }: { id: string }) {
  const r = useQuery({ queryKey: ["docs", id], queryFn: () => api.get<Row[]>("/api/documents", { vehicleId: id }) });
  return (
    <Card title="Compliance documents" actions={<Link to={`/documents?vehicleId=${id}`} className="btn btn-small">Manage documents</Link>}>
      <DataTable rows={r.data} loading={r.isLoading} columns={[
        { key: "docType", header: "Type", render: (x) => label(x.docType) },
        { key: "documentNumber", header: "Number" },
        { key: "issueDate", header: "Issued", render: (x) => date(x.issueDate) },
        { key: "expiryDate", header: "Expiry", render: (x) => date(x.expiryDate) },
        { key: "daysToExpiry", header: "Days left", align: "right", render: (x) => x.daysToExpiry ?? "—" },
        { key: "verificationStatus", header: "Verification", render: (x) => <Badge value={x.verificationStatus} /> },
      ]} />
    </Card>
  );
}

function OdometerTab({ id, onChange }: { id: string; onChange: () => void }) {
  const qc = useQueryClient();
  const r = useQuery({ queryKey: ["odo", id], queryFn: () => api.get<Row[]>(`/api/vehicles/${id}/odometer`) });
  const [form, setForm] = useState({ date: "", km: "" });
  const [msg, setMsg] = useState<Row | null>(null);
  const [error, setError] = useState<unknown>(null);
  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const res = await api.post<Row>(`/api/vehicles/${id}/odometer`, form);
      setMsg(res);
      setForm({ date: "", km: "" });
      qc.invalidateQueries({ queryKey: ["odo", id] });
      onChange();
    } catch (err) {
      setError(err);
    }
  };
  const trust = async (readingId: number, trusted: boolean) => {
    const reason = trusted ? "" : prompt("Why is this reading not trusted?") ?? "";
    await api.put(`/api/vehicles/${id}/odometer/${readingId}/trust`, { trusted, reason });
    qc.invalidateQueries({ queryKey: ["odo", id] });
    onChange();
  };
  return (
    <Card title="Odometer readings">
      <Can perm="write">
        <form className="form-grid" onSubmit={add} style={{ marginBottom: 12 }}>
          <Field label="Reading date" required><input type="date" required value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></Field>
          <Field label="Odometer (km)" required><input inputMode="decimal" required value={form.km} onChange={(e) => setForm({ ...form, km: e.target.value })} /></Field>
          <div style={{ alignSelf: "end" }}><button className="btn btn-primary">Add reading</button></div>
        </form>
      </Can>
      <ErrorBox error={error} />
      {msg && (msg.ok ? <Notice kind="success">Reading saved and trusted.</Notice> : <Notice kind="warning">Saved but not trusted: {msg.message}</Notice>)}
      <DataTable rows={r.data} loading={r.isLoading} columns={[
        { key: "readingDate", header: "Date", render: (x) => date(x.readingDate) },
        { key: "readingKm", header: "Km", align: "right", render: (x) => number(x.readingKm, 1) },
        { key: "source", header: "Source", render: (x) => label(x.source) },
        { key: "isTrusted", header: "Trusted", render: (x) => x.isVoid ? <Badge value="CANCELLED" text="void" /> : <Badge value={x.isTrusted ? "OK" : "REJECTED"} text={x.isTrusted ? "trusted" : "untrusted"} /> },
        { key: "untrustedReason", header: "Reason" },
        { key: "act", header: "", render: (x) => (
          <Can perm="manage">
            {!x.isVoid && <button className="link-btn" onClick={() => trust(x.id, !x.isTrusted)}>{x.isTrusted ? "Mark untrusted" : "Mark trusted"}</button>}
          </Can>
        ) },
      ]} />
    </Card>
  );
}

function History({ id }: { id: string }) {
  const r = useQuery({ queryKey: ["assign", id], queryFn: () => api.get<Row[]>(`/api/vehicles/${id}/assignments`) });
  return (
    <Card title="Driver, department, ownership and status history">
      <DataTable rows={r.data} loading={r.isLoading} columns={[
        { key: "assignmentType", header: "Type", render: (x) => label(x.assignmentType) },
        { key: "value", header: "Value", render: (x) => x.driverName ?? x.departmentName ?? x.vendorName ?? label(x.valueText) },
        { key: "effectiveFrom", header: "From", render: (x) => date(x.effectiveFrom) },
        { key: "effectiveTo", header: "To", render: (x) => (x.effectiveTo ? date(x.effectiveTo) : <Badge value="ACTIVE" text="current" />) },
        { key: "reason", header: "Reason" },
        { key: "changedByName", header: "Changed by" },
        { key: "createdAt", header: "Recorded", render: (x) => dateTime(x.createdAt) },
      ]} />
    </Card>
  );
}

function Aliases({ id }: { id: string }) {
  const qc = useQueryClient();
  const r = useQuery({ queryKey: ["aliases", id], queryFn: () => api.get<Row[]>(`/api/vehicles/${id}/aliases`) });
  const [alias, setAlias] = useState("");
  const [error, setError] = useState<unknown>(null);
  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post(`/api/vehicles/${id}/aliases`, { alias });
      setAlias("");
      qc.invalidateQueries({ queryKey: ["aliases", id] });
    } catch (err) {
      setError(err);
    }
  };
  const remove = async (aliasId: number) => {
    await api.del(`/api/vehicles/${id}/aliases/${aliasId}`);
    qc.invalidateQueries({ queryKey: ["aliases", id] });
  };
  return (
    <Card title="GPS / spreadsheet names (aliases)">
      <p className="muted small">Imports match these names to this vehicle without creating duplicates.</p>
      <ErrorBox error={error} />
      <ul>
        {(r.data ?? []).map((a) => (
          <li key={a.id}>{a.aliasRaw} <span className="muted mono small">({a.aliasNormalized})</span>{" "}
            <Can perm="manage"><button className="link-btn" onClick={() => remove(a.id)}>remove</button></Can>
          </li>
        ))}
        {r.data?.length === 0 && <li className="muted">None</li>}
      </ul>
      <Can perm="write">
        <form onSubmit={add} style={{ display: "flex", gap: 8 }}>
          <input placeholder="e.g. DL1RT1234 (CNG)" value={alias} onChange={(e) => setAlias(e.target.value)} aria-label="New alias" />
          <button className="btn" disabled={!alias.trim()}>Add</button>
        </form>
      </Can>
    </Card>
  );
}

function AlertsTab({ id }: { id: string }) {
  const r = useQuery({ queryKey: ["valerts", id], queryFn: () => api.get<Page<Row>>("/api/alerts", { vehicleId: id, size: 100 }) });
  return (
    <DataTable rows={r.data?.items} loading={r.isLoading} onRowClick={(a) => (window.location.href = `/alerts/${a.id}`)} columns={[
      { key: "severity", header: "Severity", render: (a) => <Badge value={a.severity} /> },
      { key: "title", header: "Alert" },
      { key: "status", header: "Status", render: (a) => <Badge value={a.status} /> },
      { key: "lastDetectedAt", header: "Last detected", render: (a) => dateTime(a.lastDetectedAt) },
    ]} />
  );
}

function DeactivateModal({ id, onClose, onDone }: { id: string; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<unknown>(null);
  const go = async () => {
    try {
      await api.post(`/api/vehicles/${id}/deactivate`, { reason });
      onDone();
      onClose();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Modal title="Deactivate vehicle" onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn btn-danger" disabled={!reason.trim()} onClick={go}>Deactivate</button>
    </>}>
      <ErrorBox error={error} />
      <p>The vehicle and its history are kept. It will be excluded from active-fleet counts and alerts.</p>
      <Field label="Reason" required><input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
    </Modal>
  );
}
