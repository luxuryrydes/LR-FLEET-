import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, download, type Row } from "../lib/api";
import { filtersToQuery } from "../lib/filters";
import { date, number } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { DataTable } from "../components/DataTable";
import { FilterBar } from "../components/FilterBar";
import { Badge, ErrorBox, PageHeader, Tabs } from "../components/ui";

type Due = "" | "OVERDUE" | "DUE_SOON" | "OK" | "MISSING_SERVICE_RECORD,MISSING_BASELINE,UNRELIABLE_BASELINE";

export function ServiceDuePage() {
  const { filters, setFilters, extra, update } = useUrlFilters(["due"]);
  const [err, setErr] = useState<unknown>(null);
  const navigate = useNavigate();
  const base = filtersToQuery({ ...filters, from: "", to: "" });
  const all = useQuery({ queryKey: ["service-status", base], queryFn: () => api.get<Row[]>("/api/service-status", base) });
  const due = extra.due as Due;
  const rows = (all.data ?? []).filter((r) => !due || due.split(",").includes(r.status));
  const count = (s: string) => (all.data ?? []).filter((r) => s.split(",").includes(r.status)).length;

  return (
    <>
      <PageHeader title="Upcoming & overdue service"
        subtitle="Current odometer = latest trusted reading + GPS km after it. Default interval 10,000 km, due soon within 500 km (configurable)."
        actions={<button className="btn" onClick={() => download("/api/service-status/export", { ...base, due, format: "xlsx" }).catch(setErr)}>Export Excel</button>} />
      <FilterBar value={filters} onApply={setFilters} hideDates show={["fuelType", "category", "departmentId", "status"]} />
      <Tabs<Due> value={due} onChange={(v) => update({ due: v })} tabs={[
        { key: "", label: "All", count: all.data?.length },
        { key: "OVERDUE", label: "Overdue", count: count("OVERDUE") },
        { key: "DUE_SOON", label: "Due soon", count: count("DUE_SOON") },
        { key: "MISSING_SERVICE_RECORD,MISSING_BASELINE,UNRELIABLE_BASELINE", label: "Missing record / baseline", count: count("MISSING_SERVICE_RECORD,MISSING_BASELINE,UNRELIABLE_BASELINE") },
        { key: "OK", label: "Service OK", count: count("OK") },
      ]} />
      <ErrorBox error={all.error ?? err} />
      <DataTable rows={rows} loading={all.isLoading} rowKey={(r) => r.vehicleId} onRowClick={(r) => navigate(`/vehicles/${r.vehicleId}`)}
        columns={[
          { key: "registrationNumber", header: "Vehicle", render: (r) => <strong className="mono">{r.registrationNumber}</strong> },
          { key: "status", header: "Status", render: (r) => <Badge value={r.status} /> },
          { key: "lastServiceDate", header: "Last service", render: (r) => r.lastServiceDate ? `${date(r.lastServiceDate)} · ${number(r.lastServiceOdometer, 0)} km` : "—" },
          { key: "currentOdometer", header: "Current est. km", align: "right", render: (r) => number(r.currentOdometer, 0) },
          { key: "nextServiceOdometer", header: "Next service km", align: "right", render: (r) => number(r.nextServiceOdometer, 0) },
          { key: "remainingKm", header: "Remaining km", align: "right", render: (r) => <span style={{ color: r.remainingKm < 0 ? "var(--red)" : undefined }}>{number(r.remainingKm, 0)}</span> },
          { key: "nextServiceDate", header: "Next date", render: (r) => date(r.nextServiceDate) },
          { key: "daysRemaining", header: "Days", align: "right", render: (r) => r.daysRemaining ?? "—" },
          { key: "reasons", header: "Why", render: (r) => <span className="muted small">{(r.reasons as string[]).join("; ")}</span> },
        ]} />
    </>
  );
}
