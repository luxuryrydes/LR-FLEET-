import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, type Page, type Row } from "../lib/api";
import { dateTime, label } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { ErrorBox, Modal, PageHeader } from "../components/ui";

export function AuditPage() {
  const [entityType, setEntityType] = useState("");
  const [entityId, setEntityId] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<Row | null>(null);
  const q = { entityType, entityId, page, size: 50 };
  const r = useQuery({ queryKey: ["audit", q], queryFn: () => api.get<Page<Row>>("/api/audit-logs", q) });
  return (
    <>
      <PageHeader title="Audit log" subtitle="Who changed what and when, including import replacements and voids." />
      <div className="filter-bar">
        <label><span>Entity type</span>
          <select value={entityType} onChange={(e) => { setEntityType(e.target.value); setPage(0); }} aria-label="Entity type">
            <option value="">All</option>
            {["VEHICLE", "GPS_DAILY_KM", "FUEL_TRANSACTION", "MAINTENANCE_RECORD", "DOCUMENT", "IMPORT_BATCH", "USER", "SETTING", "ODOMETER_READING",
              "VEHICLE_ALIAS", "DRIVER", "VENDOR", "DEPARTMENT"].map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
        </label>
        <label><span>Record id</span><input value={entityId} onChange={(e) => { setEntityId(e.target.value); setPage(0); }} /></label>
      </div>
      <ErrorBox error={r.error} />
      <DataTable rows={r.data?.items} loading={r.isLoading} total={r.data?.total} page={page} size={50} onPage={setPage} onRowClick={setOpen}
        columns={[
          { key: "createdAt", header: "When", render: (x) => dateTime(x.createdAt) },
          { key: "userEmail", header: "User" },
          { key: "action", header: "Action", render: (x) => label(x.action) },
          { key: "entityType", header: "Entity", render: (x) => label(x.entityType) },
          { key: "entityId", header: "Id" },
          { key: "ipAddress", header: "IP" },
        ]} />
      {open && (
        <Modal title={`${label(open.action)} ${label(open.entityType)} ${open.entityId ?? ""}`} onClose={() => setOpen(null)}>
          <div className="grid grid-2">
            <div><h3 style={{ fontSize: 13 }}>Before</h3><pre className="small" style={{ whiteSpace: "pre-wrap" }}>{JSON.stringify(open.beforeData, null, 2) ?? "—"}</pre></div>
            <div><h3 style={{ fontSize: 13 }}>After</h3><pre className="small" style={{ whiteSpace: "pre-wrap" }}>{JSON.stringify(open.afterData, null, 2) ?? "—"}</pre></div>
          </div>
        </Modal>
      )}
    </>
  );
}
