import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, download, type Page, type Row } from "../lib/api";
import { Can, can, useAuth } from "../lib/auth";
import { filtersToQuery } from "../lib/filters";
import { date, label, number } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { DataTable } from "../components/DataTable";
import { DeleteVehicleButton, VehicleDeleteBar, useVehicleSelection } from "../components/VehicleDelete";
import { FilterBar } from "../components/FilterBar";
import { Badge, ErrorBox, PageHeader } from "../components/ui";

export function VehiclesPage() {
  const { filters, setFilters, page, sort, update, extra } = useUrlFilters(["q"]);
  const navigate = useNavigate();
  const [exportError, setExportError] = useState<unknown>(null);
  const q = filtersToQuery({ ...filters, from: "", to: "" }, { q: extra.q, page, size: 25, sort });
  const list = useQuery({ queryKey: ["vehicles", q], queryFn: () => api.get<Page<Row>>("/api/vehicles", q) });
  const { user } = useAuth();
  const sel = useVehicleSelection();
  const deletedView = filters.status === "DELETED";
  const pick = (r: Row) => ({ id: r.id as number, registrationNumber: r.registrationNumber as string });
  const empty = !deletedView && list.data?.total === 0;
  const deletedCount = useQuery({ queryKey: ["vehicles", "deleted-count"], enabled: empty,
    queryFn: () => api.get<Page<Row>>("/api/vehicles", { status: "DELETED", size: 1 }) });

  const exportAs = (format: string) =>
    download("/api/vehicles/export", { ...filtersToQuery({ ...filters, from: "", to: "" }, { q: extra.q }), format }, `vehicles.${format}`)
      .catch(setExportError);

  return (
    <>
      <PageHeader
        title="Vehicle register"
        subtitle="Registration numbers are stored normalised (uppercase, no spaces or punctuation)."
        actions={
          <>
            <button className="btn" onClick={() => exportAs("xlsx")}>Export Excel</button>
            <button className="btn" onClick={() => exportAs("csv")}>Export CSV</button>
            <Can perm="write">
              <Link className="btn" to="/imports/new?type=VEHICLE_MASTER">Upload vehicle master sheet</Link>
              <Link className="btn btn-primary" to="/vehicles/new">+ Add vehicle</Link>
            </Can>
          </>
        }
      />
      <FilterBar value={filters} onApply={(f) => { setFilters(f); sel.clear(); }} hideDates />
      <ErrorBox error={list.error ?? exportError} />
      {deletedView && <p className="muted small">Deleted vehicles are hidden everywhere else. Tick vehicles to restore them
        {can(user, "admin") ? ", or delete permanently those without any records" : ""}.</p>}
      <VehicleDeleteBar sel={sel} deletedView={deletedView} />
      {empty && Number(deletedCount.data?.total) > 0 && (
        <div className="alert alert-warning">
          No vehicles match, but <strong>{number(deletedCount.data?.total, 0)} vehicles are deleted</strong>.{" "}
          <button className="link-btn" onClick={() => setFilters({ ...filters, status: "DELETED" })}>Show deleted vehicles</button>
          {" "}to restore them, or upload the vehicle master sheet again and keep "Restore them" ticked.
        </div>
      )}
      <DataTable
        loading={list.isLoading}
        rows={list.data?.items}
        total={list.data?.total}
        page={page}
        size={25}
        sort={sort}
        onSort={(s) => update({ sort: s })}
        onPage={(p) => update({ page: p })}
        onRowClick={(r) => navigate(`/vehicles/${r.id}`)}
        select={can(user, "manage") ? { sel, pick } : undefined}
        columns={[
          { key: "registrationNumber", header: "Registration", sortable: true, render: (r) => <strong className="mono">{r.registrationNumber}</strong> },
          { key: "make", header: "Make / model", sortable: true, render: (r) => `${r.make} ${r.model}${r.modelYear ? ` (${r.modelYear})` : ""}` },
          { key: "fuelType", header: "Fuel", sortable: true, render: (r) => label(r.fuelType) },
          { key: "categoryCode", header: "Category", render: (r) => label(r.categoryCode) },
          { key: "ownershipType", header: "Ownership", render: (r) => label(r.ownershipType) },
          { key: "department", header: "Department", sortable: true, render: (r) => r.departmentName ?? "—" },
          { key: "driverName", header: "Driver / vendor", render: (r) => r.driverName ?? r.vendorName ?? "—" },
          { key: "operatorType", header: "Driver type", render: (r) => <>{label(r.operatorType)}{r.assignmentLabel && <span className="muted small"> ({r.assignmentLabel})</span>}</> },
          { key: "gpsKmTracked", header: "GPS km", render: (r) => r.operatorType === "VENDOR"
              ? <span className="num nowrap">0 km <span className="tag-vendor">Vendor</span></span>
              : r.gpsKmTracked ? <Badge value="OK" text="imported" /> : <Badge value="DUPLICATE" text="not imported" /> },
          { key: "expectedMileage", header: "Expected mileage", align: "right", render: (r) => number(r.expectedMileage, 2) },
          { key: "status", header: "Status", sortable: true, render: (r) => <><Badge value={r.status} />{r.disposalDate && <span className="muted small"> {date(r.disposalDate)}</span>}
            {r.status === "DELETED" && <div className="muted small">{date(r.deletedAt)}{r.deleteReason ? ` · ${r.deleteReason}` : ""}</div>}</> },
          ...(can(user, "manage") && !deletedView ? [{ key: "delete", header: "Delete", align: "center" as const, render: (r: Row) => <DeleteVehicleButton v={pick(r)} /> }] : []),
        ]}
      />
    </>
  );
}
