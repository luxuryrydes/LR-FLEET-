import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, download, type Page, type Row } from "../lib/api";
import { Can } from "../lib/auth";
import { filtersToQuery } from "../lib/filters";
import { label, number } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { DataTable } from "../components/DataTable";
import { FilterBar } from "../components/FilterBar";
import { Badge, ErrorBox, PageHeader } from "../components/ui";

export function VehiclesPage() {
  const { filters, setFilters, page, sort, update, extra } = useUrlFilters(["q"]);
  const navigate = useNavigate();
  const [exportError, setExportError] = useState<unknown>(null);
  const q = filtersToQuery({ ...filters, from: "", to: "" }, { q: extra.q, page, size: 25, sort });
  const list = useQuery({ queryKey: ["vehicles", q], queryFn: () => api.get<Page<Row>>("/api/vehicles", q) });

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
            <Can perm="write"><Link className="btn btn-primary" to="/vehicles/new">+ Add vehicle</Link></Can>
          </>
        }
      />
      <FilterBar value={filters} onApply={setFilters} hideDates />
      <ErrorBox error={list.error ?? exportError} />
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
        columns={[
          { key: "registrationNumber", header: "Registration", sortable: true, render: (r) => <strong className="mono">{r.registrationNumber}</strong> },
          { key: "make", header: "Make / model", sortable: true, render: (r) => `${r.make} ${r.model}${r.modelYear ? ` (${r.modelYear})` : ""}` },
          { key: "fuelType", header: "Fuel", sortable: true, render: (r) => label(r.fuelType) },
          { key: "categoryCode", header: "Category", render: (r) => label(r.categoryCode) },
          { key: "ownershipType", header: "Ownership", render: (r) => label(r.ownershipType) },
          { key: "department", header: "Department", sortable: true, render: (r) => r.departmentName ?? "—" },
          { key: "driverName", header: "Driver", render: (r) => r.driverName ?? "—" },
          { key: "expectedMileage", header: "Expected mileage", align: "right", render: (r) => number(r.expectedMileage, 2) },
          { key: "status", header: "Status", sortable: true, render: (r) => <Badge value={r.status} /> },
        ]}
      />
    </>
  );
}
