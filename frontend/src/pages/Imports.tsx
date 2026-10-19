import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, type Page, type Row } from "../lib/api";
import { dateTime, label, number } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { Badge, ErrorBox, PageHeader, Select } from "../components/ui";

export function ImportsPage() {
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const navigate = useNavigate();
  const q = { type, page, size: 25 };
  const list = useQuery({ queryKey: ["imports", q], queryFn: () => api.get<Page<Row>>("/api/imports", q), refetchInterval: 5000 });
  return (
    <>
      <PageHeader title="Imports" subtitle="Every upload is retained with its file, row counts, status and who committed it."
        actions={<>
          <Link className="btn" to="/imports/new?type=FUEL">Import fuel file</Link>
          <Link className="btn btn-primary" to="/imports/new?type=GPS_KM">Import GPS km file</Link>
        </>} />
      <div className="actions" style={{ marginBottom: 10 }}>
        <Select value={type} onChange={(v) => { setType(v); setPage(0); }} placeholder="All types" aria-label="Import type" style={{ width: 180 }}
                options={[{ value: "GPS_KM", label: "GPS km" }, { value: "FUEL", label: "Fuel" }]} />
      </div>
      <ErrorBox error={list.error} />
      <DataTable rows={list.data?.items} loading={list.isLoading} total={list.data?.total} page={page} onPage={setPage}
        onRowClick={(r) => navigate(`/imports/${r.id}`)}
        columns={[
          { key: "id", header: "#" },
          { key: "importType", header: "Type", render: (r) => label(r.importType) },
          { key: "originalFilename", header: "File" },
          { key: "status", header: "Status", render: (r) => <Badge value={r.status} /> },
          { key: "totalRows", header: "Rows", align: "right", render: (r) => number(r.totalRows, 0) },
          { key: "insertedRows", header: "Inserted", align: "right", render: (r) => number(r.insertedRows, 0) },
          { key: "replacedRows", header: "Replaced", align: "right", render: (r) => number(r.replacedRows, 0) },
          { key: "skippedRows", header: "Skipped", align: "right", render: (r) => number(r.skippedRows, 0) },
          { key: "rejectedRows", header: "Rejected", align: "right", render: (r) => number(r.rejectedRows, 0) },
          { key: "createdByName", header: "Uploaded by" },
          { key: "createdAt", header: "Uploaded", render: (r) => dateTime(r.createdAt) },
        ]} />
    </>
  );
}
