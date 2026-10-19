import { useState } from "react";
import { defaultFilters, validateRange, type FleetFilters } from "../lib/filters";
import { useLookups } from "../lib/hooks";
import { codes } from "./ui";

interface Props {
  value: FleetFilters;
  onApply: (f: FleetFilters) => void;
  /** Which optional filters to show (dates and vehicle are always shown). */
  show?: (keyof FleetFilters)[];
  hideDates?: boolean;
}

const ALL: (keyof FleetFilters)[] = ["fuelType", "category", "ownership", "operatorType", "vendorId", "driverId", "departmentId", "status"];

export function FilterBar({ value, onApply, show = ALL, hideDates }: Props) {
  const [draft, setDraft] = useState<FleetFilters>(value);
  const [error, setError] = useState<string | null>(null);
  const { data: lk } = useLookups();
  const set = (k: keyof FleetFilters) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setDraft({ ...draft, [k]: e.target.value });

  const apply = (e?: React.FormEvent) => {
    e?.preventDefault();
    const err = hideDates ? null : validateRange(draft);
    setError(err);
    if (!err) onApply(draft);
  };
  const reset = () => {
    const d = defaultFilters();
    setDraft(d);
    setError(null);
    onApply(d);
  };

  return (
    <form className="filter-bar" onSubmit={apply} aria-label="Filters">
      {!hideDates && (
        <>
          <label>
            <span>From</span>
            <input type="date" value={draft.from} onChange={set("from")} aria-label="From date" />
          </label>
          <label>
            <span>To</span>
            <input type="date" value={draft.to} onChange={set("to")} aria-label="To date" />
          </label>
        </>
      )}
      <label>
        <span>Vehicle</span>
        <input type="search" placeholder="e.g. DL 1C AB 1234" value={draft.vehicle} onChange={set("vehicle")} aria-label="Vehicle number" />
      </label>
      {show.includes("fuelType") && (
        <label>
          <span>Fuel</span>
          <select value={draft.fuelType} onChange={set("fuelType")} aria-label="Fuel type">
            <option value="">All</option>
            {codes(lk?.fuelTypes).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      )}
      {show.includes("category") && (
        <label>
          <span>Category</span>
          <select value={draft.category} onChange={set("category")} aria-label="Category">
            <option value="">All</option>
            {lk?.vehicleCategories.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
          </select>
        </label>
      )}
      {show.includes("ownership") && (
        <label>
          <span>Ownership</span>
          <select value={draft.ownership} onChange={set("ownership")} aria-label="Ownership">
            <option value="">All</option>
            {codes(lk?.ownershipTypes).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      )}
      {show.includes("operatorType") && (
        <label>
          <span>Driver type</span>
          <select value={draft.operatorType} onChange={set("operatorType")} aria-label="Driver type">
            <option value="">All</option>
            {codes(lk?.operatorTypes).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      )}
      {show.includes("vendorId") && (
        <label>
          <span>Vendor</span>
          <select value={draft.vendorId} onChange={set("vendorId")} aria-label="Vendor">
            <option value="">All</option>
            {lk?.vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
          </select>
        </label>
      )}
      {show.includes("driverId") && (
        <label>
          <span>Driver</span>
          <select value={draft.driverId} onChange={set("driverId")} aria-label="Driver">
            <option value="">All</option>
            {lk?.drivers.map((d) => <option key={d.id} value={d.id}>{d.fullName}</option>)}
          </select>
        </label>
      )}
      {show.includes("departmentId") && (
        <label>
          <span>Department</span>
          <select value={draft.departmentId} onChange={set("departmentId")} aria-label="Department">
            <option value="">All</option>
            {lk?.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
      )}
      {show.includes("status") && (
        <label>
          <span>Status</span>
          <select value={draft.status} onChange={set("status")} aria-label="Vehicle status">
            <option value="">All</option>
            {codes(lk?.vehicleStatuses).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      )}
      <div className="filter-actions">
        <button type="submit" className="btn btn-primary">Apply</button>
        <button type="button" className="btn" onClick={reset}>Reset</button>
      </div>
      {error && <div className="field-error filter-error" role="alert">{error}</div>}
    </form>
  );
}
