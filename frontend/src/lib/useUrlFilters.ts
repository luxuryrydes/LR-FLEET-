import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { filtersFromParams, filtersToSearch, type FleetFilters } from "./filters";

/** Filters live in the URL so a KPI click or a shared link opens exactly the same filtered records. */
export function useUrlFilters(extraKeys: string[] = []) {
  const [params, setParams] = useSearchParams();
  const filters = useMemo(() => filtersFromParams(params), [params]);
  const extra = useMemo(() => Object.fromEntries(extraKeys.map((k) => [k, params.get(k) ?? ""])), [params, extraKeys.join(",")]); // eslint-disable-line react-hooks/exhaustive-deps
  const page = Number(params.get("page") ?? 0) || 0;
  const sort = params.get("sort") ?? "";

  const update = useCallback(
    (next: Partial<FleetFilters> & Record<string, string | number | undefined>) => {
      const merged: Record<string, string> = {};
      params.forEach((v, k) => (merged[k] = v));
      for (const [k, v] of Object.entries(next)) {
        if (v === undefined || v === null || v === "") delete merged[k];
        else merged[k] = String(v);
      }
      if (!("page" in next)) delete merged.page;
      setParams(merged, { replace: false });
    },
    [params, setParams],
  );

  const setFilters = useCallback((f: FleetFilters) => {
    const s = new URLSearchParams(filtersToSearch(f));
    for (const k of extraKeys) {
      const v = params.get(k);
      if (v) s.set(k, v);
    }
    setParams(s);
  }, [params, setParams, extraKeys.join(",")]); // eslint-disable-line react-hooks/exhaustive-deps

  return { filters, extra, page, sort, update, setFilters };
}
