import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, download, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { useLookups } from "../lib/hooks";
import { date, label } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { VehiclePicker } from "../components/VehiclePicker";
import { Badge, ErrorBox, Field, Modal, PageHeader, Tabs, codes } from "../components/ui";

type Bucket = "expired" | "expiring" | "missing" | "pendingVerification" | "driverLicences" | "vehicle";

export function DocumentsPage() {
  const [params] = useSearchParams();
  const vehicleId = params.get("vehicleId");
  const [tab, setTab] = useState<Bucket>(vehicleId ? "vehicle" : "expired");
  const [adding, setAdding] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const c = useQuery({ queryKey: ["compliance"], queryFn: () => api.get<Record<string, Row[]>>("/api/documents/compliance") });
  const vdocs = useQuery({ queryKey: ["docs", vehicleId], queryFn: () => api.get<Row[]>("/api/documents", { vehicleId }), enabled: Boolean(vehicleId) });

  const verify = async (id: number, status: "VERIFIED" | "REJECTED") => {
    try {
      await api.post(`/api/documents/${id}/verify`, { status });
      qc.invalidateQueries({ queryKey: ["compliance"] });
      qc.invalidateQueries({ queryKey: ["docs"] });
    } catch (e) {
      setErr(e);
    }
  };
  const docCols = [
    { key: "registrationNumber", header: "Vehicle", render: (r: Row) => r.vehicleId ? <button className="link-btn mono" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>{r.registrationNumber}</button> : r.driverName ?? "—" },
    { key: "docType", header: "Document", render: (r: Row) => label(r.docType) },
    { key: "documentNumber", header: "Number" },
    { key: "expiryDate", header: "Expiry", render: (r: Row) => date(r.expiryDate) },
    { key: "daysToExpiry", header: "Days left", align: "right" as const, render: (r: Row) => r.daysToExpiry === null || r.daysToExpiry === undefined ? "—" :
        <span style={{ color: r.daysToExpiry < 0 ? "var(--red)" : r.daysToExpiry <= 7 ? "var(--amber)" : undefined }}>{r.daysToExpiry}</span> },
    { key: "verificationStatus", header: "Verification", render: (r: Row) => <Badge value={r.verificationStatus} /> },
    { key: "file", header: "File", render: (r: Row) => r.fileId ? <button className="link-btn" onClick={() => download(`/api/files/${r.fileId}`).catch(setErr)}>Download</button> : "—" },
    { key: "act", header: "", render: (r: Row) => r.verificationStatus === "PENDING" && (
      <Can perm="manage">
        <button className="link-btn" onClick={() => verify(r.id, "VERIFIED")}>Verify</button> · <button className="link-btn" onClick={() => verify(r.id, "REJECTED")}>Reject</button>
      </Can>
    ) },
  ];
  const d = c.data;
  return (
    <>
      <PageHeader title="Compliance documents" subtitle={`Insurance, fitness, PUC, permits, registration and driver licences. Reminders at ${(d?.reminderDays as unknown as string[] | undefined)?.join(", ") ?? "30, 15, 7"} days.`}
        actions={<>
          <button className="btn" onClick={() => download("/api/reports/document-expiry/export", { format: "xlsx" }).catch(setErr)}>Export Excel</button>
          <Can perm="write"><button className="btn btn-primary" onClick={() => setAdding(true)}>+ Add document</button></Can>
        </>} />
      <Tabs<Bucket> value={tab} onChange={setTab} tabs={[
        ...(vehicleId ? [{ key: "vehicle" as Bucket, label: "This vehicle", count: vdocs.data?.length }] : []),
        { key: "expired", label: "Expired", count: d?.expired.length },
        { key: "expiring", label: "Expiring soon", count: d?.expiring.length },
        { key: "missing", label: "Missing", count: d?.missing.length },
        { key: "pendingVerification", label: "Awaiting verification", count: d?.pendingVerification.length },
        { key: "driverLicences", label: "Driver licences", count: d?.driverLicences.length },
      ]} />
      <ErrorBox error={c.error ?? err} />
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
      {adding && <DocumentModal initialVehicleId={vehicleId} onClose={() => setAdding(false)} />}
    </>
  );
}

function DocumentModal({ onClose }: { initialVehicleId: string | null; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: lk } = useLookups();
  const [vehicle, setVehicle] = useState<Row | null>(null);
  const [f, setF] = useState({ docType: "INSURANCE", documentNumber: "", issuer: "", issueDate: "", expiryDate: "", remarks: "", fileId: "", driverId: "" });
  const [error, setError] = useState<unknown>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });
  const upload = async (file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("purpose", "VEHICLE_DOCUMENT");
    try {
      const r = await api.upload<{ id: number }>("/api/files", form);
      setF((x) => ({ ...x, fileId: String(r.id) }));
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
        fileId: f.fileId ? Number(f.fileId) : null, remarks: n(f.remarks),
      });
      qc.invalidateQueries({ queryKey: ["compliance"] });
      qc.invalidateQueries({ queryKey: ["docs"] });
      onClose();
    } catch (e) {
      setError(e);
    }
  };
  const isLicence = f.docType === "DRIVING_LICENCE";
  return (
    <Modal title="Add compliance document" onClose={onClose} footer={<>
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
