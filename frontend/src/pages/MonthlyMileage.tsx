import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api, download, type Row } from "../lib/api";
import { filtersFromParams, filtersToQuery, type FleetFilters } from "../lib/filters";
import { label, month as monthLabel, number, rupees } from "../lib/format";
import { FilterBar } from "../components/FilterBar";
import { Badge, Card, ErrorBox, PageHeader, Spinner, Tabs } from "../components/ui";

type View = "" | "OK" | "MIXED_FUEL" | "NO_KM" | "NO_FUEL" | "BELOW" | "IMPLAUSIBLE";
type SortKey = "registrationNumber" | "km" | "fuelExpense" | "costPerKm" | "mileage" | "variancePct";

export function currentMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** Monthly mileage calculator: month fuel expense ÷ month GPS km, per vehicle and for the fleet. */
export function MonthlyMileagePage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const month = params.get("month") || currentMonth();
  const filters = filtersFromParams(params);
  const [view, setView] = useState<View>("");
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: "costPerKm", desc: true });
  const [err, setErr] = useState<unknown>(null);
  const q = { ...filtersToQuery({ ...filters, from: "", to: "" }), month };

  const data = useQuery({ queryKey: ["monthly-mileage", q], queryFn: () => api.get<Row>("/api/monthly-mileage", q) });
  const trend = useQuery({ queryKey: ["monthly-mileage-trend", q], queryFn: () => api.get<Row[]>("/api/monthly-mileage/trend", { ...q, months: 12 }) });

  const setMonth = (m: string) => {
    const p = new URLSearchParams(params);
    p.set("month", m);
    setParams(p);
  };
  const setFilters = (f: FleetFilters) => {
    const p = new URLSearchParams(filtersToQuery({ ...f, from: "", to: "" }) as Record<string, string>);
    p.set("month", month);
    setParams(p);
  };
  const shift = (n: number) => {
    const [y, m] = month.split("-").map(Number);
    const d = new Date(y, m - 1 + n, 1);
    setMonth(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`);
  };

  const t = data.data?.totals as Row | undefined;
  const rows = (data.data?.rows ?? []) as Row[];
  const shown = useMemo(() => {
    const f = rows.filter((r) => (view === "" ? true : view === "BELOW" ? r.belowExpected : r.status === view));
    const k = sort.key;
    return [...f].sort((a, b) => {
      const x = a[k];
      const y = b[k];
      if (x === null || x === undefined) return 1;
      if (y === null || y === undefined) return -1;
      const c = typeof x === "string" ? String(x).localeCompare(String(y)) : Number(x) - Number(y);
      return sort.desc ? -c : c;
    });
  }, [rows, view, sort]);
  const count = (s: string) => (t?.statusCounts?.[s] ?? 0) as number;
  const below = rows.filter((r) => r.belowExpected).length;
  const th = (key: SortKey, text: string, right = true) => (
    <th style={{ textAlign: right ? "right" : "left" }}>
      <button className="th-sort" onClick={() => setSort({ key, desc: sort.key === key ? !sort.desc : true })}>
        {text}<span className="sort-ind">{sort.key === key ? (sort.desc ? "▼" : "▲") : "↕"}</span>
      </button>
    </th>
  );
  const mileageCard = (e: string, unit: string) => {
    const m = t?.mileageByEnergy?.[e] as Row | undefined;
    return (
      <div className="kpi" key={e}>
        <div className="kpi-label">{e === "ELECTRICITY" ? "EV" : e} mileage</div>
        <div className="kpi-value">{m?.mileage ? `${number(m.mileage, 2)} ${unit}` : "—"}</div>
        <div className="kpi-sub">{m ? `${number(m.km, 0)} km ÷ ${number(m.quantity, 1)}` : "No single-fuel vehicles"}</div>
      </div>
    );
  };

  return (
    <>
      <PageHeader title="Monthly mileage calculator"
        subtitle="Fuel expense of the month ÷ GPS kilometres of the month, for every vehicle and the whole fleet."
        actions={<>
          <button className="btn" onClick={() => shift(-1)} aria-label="Previous month">‹</button>
          <input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} aria-label="Month" style={{ width: 170 }} />
          <button className="btn" onClick={() => shift(1)} aria-label="Next month">›</button>
          <button className="btn" onClick={() => download("/api/monthly-mileage/export", { ...q, format: "xlsx" }).catch(setErr)}>Export Excel</button>
        </>} />
      <FilterBar value={filters} onApply={setFilters} hideDates show={["fuelType", "category", "operatorType", "departmentId", "status"]} />
      <ErrorBox error={data.error ?? trend.error ?? err} />
      {data.isLoading && <Spinner />}
      {t && (
        <>
          <div className="kpis">
            <div className="kpi"><div className="kpi-label">Month</div><div className="kpi-value">{monthLabel(month)}</div>
              <div className="kpi-sub">{t.vehicles} vehicles with km or fuel</div></div>
            <div className="kpi"><div className="kpi-label">Total GPS km</div><div className="kpi-value">{number(t.totalKm, 0)}</div></div>
            <div className="kpi"><div className="kpi-label">Total fuel expense</div><div className="kpi-value">{rupees(t.totalFuelExpense)}</div></div>
            <div className="kpi good"><div className="kpi-label">Fleet fuel ₹ / km</div>
              <div className="kpi-value">{t.costPerKm === null ? "—" : rupees(t.costPerKm)}</div>
              <div className="kpi-sub">{rupees(t.includedFuelExpense)} ÷ {number(t.includedKm, 0)} km · {t.vehiclesIncluded} vehicles with both</div></div>
            {mileageCard("CNG", "km/kg")}
            {mileageCard("PETROL", "km/L")}
            {mileageCard("DIESEL", "km/L")}
            {mileageCard("ELECTRICITY", "km/kWh")}
            <div className="kpi">
              <div className="kpi-label">Mixed fuel mileage</div>
              <div className="kpi-value">{t.mixedFuel?.mileage ? `${number(t.mixedFuel.mileage, 2)} km/kgL` : "—"}</div>
              <div className="kpi-sub">{t.mixedFuel?.vehicles
                ? `${number(t.mixedFuel.km, 0)} km ÷ ${number(t.mixedFuel.quantity, 1)} kgL · ${t.mixedFuel.vehicles} CNG + petrol vehicles`
                : "No mixed-fuel vehicles"}</div>
            </div>
          </div>
          <Card title="Last 12 months">
            <div className="chart-box">
              <ResponsiveContainer>
                <ComposedChart data={(trend.data ?? []).map((r) => ({ month: monthLabel(r.month), km: Number(r.totalKm), cpk: r.costPerKm === null ? null : Number(r.costPerKm),
                  cng: r.mileageByEnergy?.CNG?.mileage ? Number(r.mileageByEnergy.CNG.mileage) : null,
                  mixed: r.mixedFuel?.mileage ? Number(r.mixedFuel.mileage) : null }))}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis yAxisId="km" fontSize={12} tickFormatter={(v) => number(v, 0)} />
                  <YAxis yAxisId="r" orientation="right" fontSize={12} tickFormatter={(v) => `₹${v}`} />
                  <Tooltip formatter={(v: number, n: string) => (n === "GPS km" ? `${number(v, 0)} km` : n === "₹ / km" ? rupees(v) : `${number(v, 2)} ${n === "Mixed km/kgL" ? "km/kgL" : "km/kg"}`)} />
                  <Legend />
                  <Bar yAxisId="km" dataKey="km" name="GPS km" fill="#9fb8d6" />
                  <Line yAxisId="r" dataKey="cpk" name="₹ / km" stroke="#b45309" strokeWidth={2} connectNulls />
                  <Line yAxisId="r" dataKey="cng" name="CNG km/kg" stroke="#059669" strokeWidth={2} connectNulls />
                  <Line yAxisId="r" dataKey="mixed" name="Mixed km/kgL" stroke="#7c3aed" strokeWidth={2} strokeDasharray="5 3" connectNulls />
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          </Card>
          <Tabs<View> value={view} onChange={setView} tabs={[
            { key: "", label: "All", count: rows.length },
            { key: "OK", label: "Calculated", count: count("OK") },
            { key: "MIXED_FUEL", label: "Mixed fuel (km/kgL)", count: count("MIXED_FUEL") },
            { key: "BELOW", label: "Below expected", count: below },
            { key: "IMPLAUSIBLE", label: "Implausible (check fuel)", count: count("IMPLAUSIBLE") },
            { key: "NO_KM", label: "Fuel but no km", count: count("NO_KM") },
            { key: "NO_FUEL", label: "Km but no fuel", count: count("NO_FUEL") },
          ]} />
          <div className="table-wrap">
            <div className="table-scroll">
              <table className="table" aria-label="Monthly mileage by vehicle">
                <thead>
                  <tr>
                    {th("registrationNumber", "Vehicle", false)}
                    <th>Fuel</th>
                    {th("km", "GPS km")}
                    <th style={{ textAlign: "right" }}>Fuel bought</th>
                    {th("fuelExpense", "Fuel expense")}
                    {th("costPerKm", "₹ / km")}
                    {th("mileage", "Mileage")}
                    <th style={{ textAlign: "right" }}>Expected</th>
                    {th("variancePct", "Variance")}
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((r) => (
                    <tr key={r.vehicleId} className="clickable" onClick={() => navigate(`/vehicles/${r.vehicleId}`)}>
                      <td><strong className="mono">{r.registrationNumber}</strong><div className="muted small">{r.vehicle} · {label(r.operatorType)}</div></td>
                      <td>{label(r.fuelType)}</td>
                      <td className="num" style={{ textAlign: "right" }}>{number(r.km, 0)}</td>
                      <td className="num" style={{ textAlign: "right" }}>
                        {[r.cngKg && `${number(r.cngKg, 2)} kg CNG`, r.petrolL && `${number(r.petrolL, 2)} L petrol`, r.dieselL && `${number(r.dieselL, 2)} L diesel`,
                          r.electricityKwh && `${number(r.electricityKwh, 1)} kWh`].filter(Boolean).join(" + ") || "—"}
                        {r.combinedQuantity !== null && r.combinedQuantity !== undefined && (
                          <div className="muted small">= {number(r.combinedQuantity, 2)} {String(r.mileageUnit ?? "km/kgL").slice(3)}</div>)}
                      </td>
                      <td className="num" style={{ textAlign: "right" }}>{rupees(r.fuelExpense)}</td>
                      <td className="num" style={{ textAlign: "right" }}><strong>{r.costPerKm === null ? "—" : rupees(r.costPerKm)}</strong></td>
                      <td className="num" style={{ textAlign: "right" }}>{r.mileage === null ? "—" : `${number(r.mileage, 2)} ${r.mileageUnit}`}</td>
                      <td className="num" style={{ textAlign: "right" }}>{r.expectedMileage === null ? "—" : number(r.expectedMileage, 2)}</td>
                      <td className="num" style={{ textAlign: "right", color: r.belowExpected ? "var(--red)" : undefined }}>
                        {r.variancePct === null ? "—" : `${Number(r.variancePct) > 0 ? "+" : ""}${number(r.variancePct, 1)}%`}
                      </td>
                      <td><Badge value={r.belowExpected ? "HIGH" : r.status}
                                 text={r.belowExpected ? "Below expected" : r.status === "IMPLAUSIBLE" ? "Implausible — check fuel records" : label(r.status)} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {shown.length === 0 && <div className="empty">No vehicles in this view for {monthLabel(month)}.</div>}
            </div>
          </div>
          <p className="muted small">
            ₹/km and mileage use the fuel bought and the GPS km recorded in the calendar month (purchase basis). A bi-fuel vehicle that bought both CNG
            and petrol adds kg and litres into one quantity (kgL) and shows km ÷ kgL as km/kgL, e.g. 246 km ÷ (27.66 kg + 23.64 L = 51.30 kgL)
            = 4.80 km/kgL. km/kgL compares mixed-fuel vehicles with each other only; it is kept out of the fleet km/kg and km/L and has no expected value. "Fuel but no km" usually means a vendor vehicle (GPS km are
            imported only for LR drivers) or a missing GPS file. "Implausible" means the month's km ÷ fuel is outside the plausible range in Settings
            (e.g. above 45 km/kg for CNG), usually because fuel bills or GPS days are missing; those vehicles are left out of the fleet mileage.
            Fleet mileage is weighted: total km ÷ total fuel, not an average of vehicles.
          </p>
        </>
      )}
    </>
  );
}
