import { daysAgoIso, todayIso } from "./format";
import type { Query } from "./api";

/** Shared filter model; field names match the backend FleetFilter so the same query drives lists, dashboard and exports. */
export interface FleetFilters {
  from: string;
  to: string;
  vehicle: string;
  fuelType: string;
  category: string;
  ownership: string;
  vendorId: string;
  driverId: string;
  departmentId: string;
  status: string;
}

export const FILTER_KEYS: (keyof FleetFilters)[] = [
  "from", "to", "vehicle", "fuelType", "category", "ownership", "vendorId", "driverId", "departmentId", "status",
];

export function defaultFilters(): FleetFilters {
  return {
    from: daysAgoIso(29), to: todayIso(), vehicle: "", fuelType: "", category: "", ownership: "",
    vendorId: "", driverId: "", departmentId: "", status: "",
  };
}

export function filtersFromParams(params: URLSearchParams): FleetFilters {
  const f = defaultFilters();
  for (const k of FILTER_KEYS) {
    const v = params.get(k);
    if (v !== null) f[k] = v;
  }
  return f;
}

/** Only non-empty values become query parameters. */
export function filtersToQuery(f: Partial<FleetFilters>, extra: Query = {}): Query {
  const q: Query = { ...extra };
  for (const k of FILTER_KEYS) {
    const v = f[k];
    if (v !== undefined && v !== null && String(v).trim() !== "") q[k] = String(v).trim();
  }
  return q;
}

export function filtersToSearch(f: Partial<FleetFilters>, extra: Query = {}): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(filtersToQuery(f, extra))) {
    if (v !== undefined && v !== null && v !== "") u.set(k, String(v));
  }
  return u.toString();
}

export function validateRange(f: Pick<FleetFilters, "from" | "to">): string | null {
  if (!f.from || !f.to) return "Choose both dates";
  if (f.from > f.to) return "'From' date must be on or before 'To' date";
  return null;
}
