import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { date, label } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { Badge, ErrorBox, Field, Modal, PageHeader, Tabs } from "../components/ui";

type Tab = "drivers" | "vendors" | "departments";

const DEFS: Record<Tab, { path: string; fields: { key: string; label: string; type?: string; options?: string[]; required?: boolean }[] }> = {
  drivers: { path: "/api/drivers", fields: [
    { key: "fullName", label: "Full name", required: true }, { key: "phone", label: "Phone" }, { key: "licenceNumber", label: "Licence no." },
    { key: "licenceExpiry", label: "Licence expiry", type: "date" }, { key: "remarks", label: "Remarks" },
  ] },
  vendors: { path: "/api/vendors", fields: [
    { key: "name", label: "Name", required: true }, { key: "vendorType", label: "Type", options: ["FUEL_STATION", "WORKSHOP", "VEHICLE_VENDOR", "OTHER"], required: true },
    { key: "contactPerson", label: "Contact person" }, { key: "phone", label: "Phone" }, { key: "email", label: "Email", type: "email" },
    { key: "gstin", label: "GSTIN" }, { key: "address", label: "Address" },
  ] },
  departments: { path: "/api/departments", fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Name", required: true }] },
};

export function MastersPage() {
  const [tab, setTab] = useState<Tab>("drivers");
  const [editing, setEditing] = useState<Row | null>(null);
  const r = useQuery({ queryKey: ["master", tab], queryFn: () => api.get<Row[]>(DEFS[tab].path, { includeInactive: true }) });
  const cols = {
    drivers: [
      { key: "fullName", header: "Driver" }, { key: "phone", header: "Phone" }, { key: "licenceNumber", header: "Licence" },
      { key: "licenceExpiry", header: "Licence expiry", render: (x: Row) => date(x.licenceExpiry) }, { key: "vehicles", header: "Vehicles" },
    ],
    vendors: [
      { key: "name", header: "Vendor" }, { key: "vendorType", header: "Type", render: (x: Row) => label(x.vendorType) },
      { key: "contactPerson", header: "Contact" }, { key: "phone", header: "Phone" }, { key: "gstin", header: "GSTIN" },
    ],
    departments: [{ key: "code", header: "Code" }, { key: "name", header: "Department" }],
  }[tab];
  return (
    <>
      <PageHeader title="Drivers, vendors & departments" actions={<Can perm="write"><button className="btn btn-primary" onClick={() => setEditing({})}>+ Add</button></Can>} />
      <Tabs<Tab> value={tab} onChange={setTab} tabs={[{ key: "drivers", label: "Drivers" }, { key: "vendors", label: "Vendors & stations" }, { key: "departments", label: "Departments" }]} />
      <ErrorBox error={r.error} />
      <DataTable rows={r.data} loading={r.isLoading} onRowClick={(x) => setEditing(x)} columns={[
        ...cols, { key: "isActive", header: "Status", render: (x: Row) => <Badge value={x.isActive ? "ACTIVE" : "INACTIVE"} /> },
      ]} />
      {editing && <MasterModal tab={tab} row={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function MasterModal({ tab, row, onClose }: { tab: Tab; row: Row; onClose: () => void }) {
  const qc = useQueryClient();
  const def = DEFS[tab];
  const [v, setV] = useState<Row>({ active: row.isActive ?? true, ...row });
  const [error, setError] = useState<unknown>(null);
  const save = async () => {
    setError(null);
    const body: Row = { active: v.active };
    for (const f of def.fields) body[f.key] = v[f.key] === "" ? null : v[f.key] ?? null;
    try {
      if (row.id) await api.put(`${def.path}/${row.id}`, body);
      else await api.post(def.path, body);
      qc.invalidateQueries({ queryKey: ["master", tab] });
      qc.invalidateQueries({ queryKey: ["lookups"] });
      onClose();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Modal title={row.id ? "Edit" : "Add"} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <Can perm="write"><button className="btn btn-primary" onClick={save}>Save</button></Can>
    </>}>
      <ErrorBox error={error} />
      <div className="form-grid">
        {def.fields.map((f) => (
          <Field key={f.key} label={f.label} required={f.required}>
            {f.options ? (
              <select value={v[f.key] ?? ""} onChange={(e) => setV({ ...v, [f.key]: e.target.value })} aria-label={f.label}>
                <option value="">Choose…</option>
                {f.options.map((o) => <option key={o} value={o}>{label(o)}</option>)}
              </select>
            ) : (
              <input type={f.type ?? "text"} value={v[f.key] ?? ""} onChange={(e) => setV({ ...v, [f.key]: e.target.value })} />
            )}
          </Field>
        ))}
        {row.id && (
          <Field label="Active">
            <select value={v.active ? "true" : "false"} onChange={(e) => setV({ ...v, active: e.target.value === "true" })} aria-label="Active">
              <option value="true">Active</option><option value="false">Inactive</option>
            </select>
          </Field>
        )}
      </div>
    </Modal>
  );
}
