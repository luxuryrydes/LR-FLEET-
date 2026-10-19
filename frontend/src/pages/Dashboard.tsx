import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api, type Row } from "../lib/api";
import { filtersToQuery, filtersToSearch } from "../lib/filters";
import { EFFICIENCY_UNIT, QUANTITY_UNIT, month, number, rupees, date } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { FilterBar } from "../components/FilterBar";
import { Card, ErrorBox, PageHeader, Spinner } from "../components/ui";

const ENERGY_COLORS: Record<string, string> = { PETROL: "#d97706", DIESEL: "#475569", CNG: "#059669", ELECTRICITY: "#2563eb" };

interface Kpis {
  period: { from: string; to: string };
  vehicles: { total: number; active: number; inactive: number; idle: number; workshop: number };
  totalKm: number;
  vehiclesWithGps: number;
  vehiclesMissingGps: number;
  serviceDueSoon: number;
  serviceOverdue: number;
  missingServiceRecords: number;
  missingOdometerBaseline: number;
  fuelByEnergy: Record<string, { quantity: number; expense: number; weightedUnitPrice: number | null }>;
  totalFuelExpense: number;
  fleetMileage: { energyType: string; mileage: number | null; eligibleKm: number; eligibleQuantity: number; vehicles: number }[];
  costPerKm: { fuelCostPerKm: number | null; maintenanceCostPerKm: number | null; fuelPlusMaintenanceCostPerKm: number | null;
    vehiclesIncluded: number; vehiclesWithoutKm: number; vehiclesWithoutFuel: number; basis: string };
  maintenanceExpense: number;
  vehiclesDeterioratingMileage: number;
  vehiclesMissingFuel: number;
}

function Kpi({ label, value, sub, to, tone }: { label: string; value: string; sub?: string; to?: string; tone?: "warn" | "bad" | "good" }) {
  const navigate = useNavigate();
  const inner = (
    <>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </>
  );
  return to ? (
    <button className={`kpi ${tone ?? ""}`} onClick={() => navigate(to)} title="Open the matching records">{inner}</button>
  ) : (
    <div className={`kpi ${tone ?? ""}`}>{inner}</div>
  );
}

export function DashboardPage() {
  const { filters, setFilters } = useUrlFilters();
  const q = filtersToQuery(filters);
  const s = (extra: Record<string, string> = {}) => filtersToSearch(filters, extra);
  const vehicleS = (extra: Record<string, string> = {}) => filtersToSearch({ ...filters, from: "", to: "" }, extra);

  const kpis = useQuery({ queryKey: ["kpis", q], queryFn: () => api.get<Kpis>("/api/dashboard/kpis", q) });
  const charts = useQuery({ queryKey: ["charts", q], queryFn: () => api.get<Record<string, Row[]>>("/api/dashboard/charts", q) });
  const k = kpis.data;

  const fuelChart = pivot(charts.data?.fuelExpenditure ?? [], "energyType", "expense");
  const mileageChart = pivot(charts.data?.mileageEfficiency ?? [], "energyType", "mileage");
  const energies = Array.from(new Set((charts.data?.fuelExpenditure ?? []).map((r) => r.energyType as string)));
  const mileageEnergies = Array.from(new Set((charts.data?.mileageEfficiency ?? []).map((r) => r.energyType as string)));

  return (
    <>
      <PageHeader title="Fleet dashboard" subtitle={k ? `${date(k.period.from)} to ${date(k.period.to)} · figures from validated records only` : undefined} />
      <FilterBar value={filters} onApply={setFilters} />
      <ErrorBox error={kpis.error} />
      {kpis.isLoading && <Spinner />}
      {k && (
        <>
          <div className="kpis">
            <Kpi label="Registered vehicles" value={number(k.vehicles.total, 0)} to={`/vehicles?${vehicleS()}`} />
            <Kpi label="Active" value={number(k.vehicles.active, 0)} tone="good" to={`/vehicles?${vehicleS({ status: "ACTIVE" })}`} />
            <Kpi label="Inactive / sold" value={number(k.vehicles.inactive, 0)} to={`/vehicles?${vehicleS({ status: "INACTIVE" })}`} />
            <Kpi label="Idle" value={number(k.vehicles.idle, 0)} to={`/vehicles?${vehicleS({ status: "IDLE" })}`} />
            <Kpi label="In workshop" value={number(k.vehicles.workshop, 0)} tone={k.vehicles.workshop ? "warn" : undefined} to={`/vehicles?${vehicleS({ status: "WORKSHOP" })}`} />
            <Kpi label="Total km travelled" value={number(k.totalKm, 0)} sub="GPS daily km (current records)" to={`/gps?${s()}`} />
            <Kpi label="Vehicles with GPS data" value={number(k.vehiclesWithGps, 0)} to={`/gps?${s()}`} />
            <Kpi label="Active, no GPS data" value={number(k.vehiclesMissingGps, 0)} tone={k.vehiclesMissingGps ? "bad" : "good"}
                 sub="Missing data is not counted as zero" to={`/alerts?type=NO_GPS_REPORT&status=ACTIVE`} />
            <Kpi label="Service due soon" value={number(k.serviceDueSoon, 0)} tone={k.serviceDueSoon ? "warn" : undefined} to={`/service-due?${vehicleS({ due: "DUE_SOON" })}`} />
            <Kpi label="Service overdue" value={number(k.serviceOverdue, 0)} tone={k.serviceOverdue ? "bad" : "good"} to={`/service-due?${vehicleS({ due: "OVERDUE" })}`} />
            <Kpi label="Missing service records" value={number(k.missingServiceRecords + k.missingOdometerBaseline, 0)}
                 sub={`${k.missingOdometerBaseline} without a reliable odometer`} tone={k.missingServiceRecords ? "warn" : undefined}
                 to={`/service-due?${vehicleS({ due: "MISSING_SERVICE_RECORD,MISSING_BASELINE,UNRELIABLE_BASELINE" })}`} />
            <Kpi label="Missing fuel records" value={number(k.vehiclesMissingFuel, 0)} sub="km recorded, no fuel in period"
                 tone={k.vehiclesMissingFuel ? "warn" : undefined} to={`/alerts?type=GPS_NO_FUEL&status=ACTIVE`} />
            {(["CNG", "PETROL", "DIESEL", "ELECTRICITY"] as const).map((e) => (
              <Kpi key={e} label={`${e === "ELECTRICITY" ? "EV energy" : e} consumed`}
                   value={`${number(k.fuelByEnergy[e]?.quantity ?? 0, 1)} ${QUANTITY_UNIT[e]}`}
                   sub={k.fuelByEnergy[e]?.weightedUnitPrice ? `${rupees(k.fuelByEnergy[e].weightedUnitPrice)} / ${QUANTITY_UNIT[e]} weighted` : "No transactions"}
                   to={`/fuel?${s({ energyType: e })}`} />
            ))}
            <Kpi label="Fuel & energy expense" value={rupees(k.totalFuelExpense)} to={`/fuel?${s()}`} />
            <Kpi label="Maintenance expense" value={rupees(k.maintenanceExpense)} to={`/maintenance?${s()}`} />
            <Kpi label="Fuel cost / km" value={k.costPerKm.fuelCostPerKm === null ? "Insufficient data" : rupees(k.costPerKm.fuelCostPerKm)}
                 sub={`${k.costPerKm.vehiclesIncluded} vehicles with km and fuel`} to={`/reports?report=fuel-cost-per-km&${s()}`} />
            <Kpi label="Fuel + maintenance / km" value={k.costPerKm.fuelPlusMaintenanceCostPerKm === null ? "Insufficient data" : rupees(k.costPerKm.fuelPlusMaintenanceCostPerKm)}
                 sub="Not total operating cost" to={`/reports?report=fuel-cost-per-km&${s()}`} />
            <Kpi label="Deteriorating mileage" value={number(k.vehiclesDeterioratingMileage, 0)} tone={k.vehiclesDeterioratingMileage ? "bad" : "good"}
                 sub="Below expected by the configured %" to={`/reports?report=low-efficiency-vehicles&${s()}`} />
          </div>

          <Card title="Average mileage by fuel type (weighted)">
            <div className="kpis" style={{ marginBottom: 0 }}>
              {k.fleetMileage.length === 0 && <span className="muted">No eligible refuelling intervals in this period.</span>}
              {k.fleetMileage.map((m) => (
                <Kpi key={m.energyType} label={m.energyType}
                     value={m.mileage === null ? "Insufficient data" : `${number(m.mileage, 2)} ${EFFICIENCY_UNIT[m.energyType]}`}
                     sub={`${number(m.eligibleKm, 0)} km ÷ ${number(m.eligibleQuantity, 1)} ${QUANTITY_UNIT[m.energyType]} · ${m.vehicles} vehicles`}
                     to={`/reports?report=fleet-mileage-comparison&${s()}`} />
              ))}
            </div>
            <p className="muted small">{k.costPerKm.basis}</p>
          </Card>
        </>
      )}

      {charts.data && (
        <div className="grid grid-2">
          <Card title="Monthly kilometres">
            <div className="chart-box">
              <ResponsiveContainer>
                <BarChart data={charts.data.monthlyKm.map((r) => ({ ...r, month: month(r.month) }))}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis fontSize={12} tickFormatter={(v) => number(v, 0)} />
                  <Tooltip formatter={(v: number) => `${number(v, 0)} km`} />
                  <Bar dataKey="km" fill="#2b5b8f" name="Km" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
          <Card title="Fuel & energy expenditure">
            <div className="chart-box">
              <ResponsiveContainer>
                <BarChart data={fuelChart}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis fontSize={12} tickFormatter={(v) => `₹${number(v / 1000, 0)}k`} />
                  <Tooltip formatter={(v: number) => rupees(v)} />
                  <Legend />
                  {energies.map((e) => <Bar key={e} dataKey={e} stackId="a" fill={ENERGY_COLORS[e]} />)}
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
          <Card title="Mileage efficiency (weighted, by fuel)">
            <div className="chart-box">
              <ResponsiveContainer>
                <LineChart data={mileageChart}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis fontSize={12} />
                  <Tooltip formatter={(v: number, n: string) => `${number(v, 2)} ${EFFICIENCY_UNIT[n] ?? ""}`} />
                  <Legend />
                  {mileageEnergies.map((e) => <Line key={e} dataKey={e} stroke={ENERGY_COLORS[e]} strokeWidth={2} connectNulls />)}
                </LineChart>
              </ResponsiveContainer>
            </div>
          </Card>
          <Card title="Maintenance expenditure">
            <div className="chart-box">
              <ResponsiveContainer>
                <BarChart data={charts.data.maintenanceExpenditure.map((r) => ({ ...r, month: month(r.month) }))}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis fontSize={12} tickFormatter={(v) => `₹${number(v / 1000, 0)}k`} />
                  <Tooltip formatter={(v: number) => rupees(v)} />
                  <Bar dataKey="amount" fill="#b45309" name="Maintenance" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
          <Card title="Vehicle utilisation">
            <div className="chart-box">
              <ResponsiveContainer>
                <LineChart data={charts.data.utilisation.map((r) => ({ ...r, month: month(r.month) }))}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis fontSize={12} domain={[0, 100]} unit="%" />
                  <Tooltip formatter={(v: number) => `${number(v, 1)}%`} />
                  <Line dataKey="utilisationPct" stroke="#15803d" strokeWidth={2} name="Days with km / vehicle-days" />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </div>
      )}
    </>
  );
}

/** [{month, energyType, value}] -> [{month, PETROL: v, CNG: v}] */
function pivot(rows: Row[], seriesKey: string, valueKey: string): Row[] {
  const byMonth = new Map<string, Row>();
  for (const r of rows) {
    const m = month(r.month);
    const entry = byMonth.get(m) ?? { month: m };
    entry[r[seriesKey]] = r[valueKey] === null ? null : Number(r[valueKey]);
    byMonth.set(m, entry);
  }
  return Array.from(byMonth.values());
}
