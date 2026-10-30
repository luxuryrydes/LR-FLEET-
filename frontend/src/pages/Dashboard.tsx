import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api, type Row } from "../lib/api";
import { useAuth } from "../lib/auth";
import { axis, bar, FUEL_COLORS, FUEL_LABEL, grid, legend, SERIES_1, tooltip } from "../lib/chartTheme";
import { filtersToQuery, filtersToSearch } from "../lib/filters";
import { EFFICIENCY_UNIT, QUANTITY_UNIT, date, month, number, rupees } from "../lib/format";
import { useUrlFilters } from "../lib/useUrlFilters";
import { FilterBar } from "../components/FilterBar";
import { Icon } from "../components/Icon";
import { ErrorBox, Spinner } from "../components/ui";

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

const FUELS = ["CNG", "PETROL", "DIESEL", "ELECTRICITY"] as const;
/** Whole rupees for headline totals (exact paise stay in the lists and exports). */
const wholeRupees = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `₹${number(Math.round(Number(v)), 0)}`);

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
}

function HeroTile({ icon, label, value, sub, to, tint }: { icon: string; label: string; value: string; sub?: string; to: string; tint: string }) {
  return (
    <Link className="kpi hero-tile" to={to}>
      <span className="kpi-icon" style={{ background: `${tint}1a`, color: tint }}><Icon name={icon} size={20} /></span>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </Link>
  );
}

type Tone = "bad" | "warn" | "good";
function AttentionItem({ icon, label, hint, count, to, tone }: { icon: string; label: string; hint: string; count: number; to: string; tone: Tone }) {
  const t: Tone = count === 0 ? "good" : tone;
  return (
    <Link className={`attention-item tone-${t}`} to={to}>
      <span className="attention-icon"><Icon name={count === 0 ? "check" : icon} size={16} strokeWidth={2.2} /></span>
      <span className="attention-text"><strong>{label}</strong><span>{count === 0 ? "All clear" : hint}</span></span>
      <span className="attention-count">{number(count, 0)}</span>
      <Icon name="arrow" size={15} />
    </Link>
  );
}

function ChartCard({ title, sub, children }: { title: string; sub?: string; children: React.ReactNode }) {
  return (
    <section className="card chart-card">
      <div className="card-head"><div><h2>{title}</h2>{sub && <div className="card-sub">{sub}</div>}</div></div>
      <div className="chart-box">{children}</div>
    </section>
  );
}

export function DashboardPage() {
  const { user } = useAuth();
  const { filters, setFilters } = useUrlFilters();
  const [showFilters, setShowFilters] = useState(false);
  const q = filtersToQuery(filters);
  const s = (extra: Record<string, string> = {}) => filtersToSearch(filters, extra);
  const vehicleS = (extra: Record<string, string> = {}) => filtersToSearch({ ...filters, from: "", to: "" }, extra);

  const kpis = useQuery({ queryKey: ["kpis", q], queryFn: () => api.get<Kpis>("/api/dashboard/kpis", q) });
  const charts = useQuery({ queryKey: ["charts", q], queryFn: () => api.get<Record<string, Row[]>>("/api/dashboard/charts", q) });
  const docs = useQuery({ queryKey: ["compliance"], queryFn: () => api.get<Record<string, Row[]>>("/api/documents/compliance") });
  const k = kpis.data;

  const fuelChart = pivot(charts.data?.fuelExpenditure ?? [], "energyType", "expense");
  const mileageChart = pivot(charts.data?.mileageEfficiency ?? [], "energyType", "mileage");
  const energies = FUELS.filter((e) => (charts.data?.fuelExpenditure ?? []).some((r) => r.energyType === e));
  const mileageEnergies = FUELS.filter((e) => (charts.data?.mileageEfficiency ?? []).some((r) => r.energyType === e));

  const v = k?.vehicles;
  const segments = v ? [
    { key: "ACTIVE", label: "Active", n: v.active, cls: "seg-active" },
    { key: "IDLE", label: "Idle", n: v.idle, cls: "seg-idle" },
    { key: "WORKSHOP", label: "In workshop", n: v.workshop, cls: "seg-workshop" },
    { key: "INACTIVE", label: "Inactive / sold", n: v.inactive, cls: "seg-inactive" },
  ] : [];
  const firstName = (user?.fullName ?? "").split(" ")[0];

  return (
    <>
      <div className="page-header dash-header">
        <div>
          <h1>{greeting()}{firstName ? `, ${firstName}` : ""}</h1>
          <p>{k ? <>Fleet overview for <strong>{date(k.period.from)} – {date(k.period.to)}</strong>. Every figure opens the records behind it.</> : "Fleet overview"}</p>
        </div>
        <div className="actions">
          <button className="btn" onClick={() => setShowFilters(!showFilters)} aria-expanded={showFilters}>
            <Icon name="calendar" size={15} /> {showFilters ? "Hide filters" : "Change period / filters"}
          </button>
          <Link className="btn btn-primary" to="/monthly-mileage"><Icon name="gauge" size={15} /> Monthly mileage</Link>
        </div>
      </div>
      {showFilters && <FilterBar value={filters} onApply={setFilters} />}
      <ErrorBox error={kpis.error} />
      {kpis.isLoading && <Spinner />}
      {k && v && (
        <>
          <div className="hero-kpis">
            <HeroTile icon="route" tint="#2a78d6" label="Kilometres travelled" value={`${number(k.totalKm, 0)} km`}
                      sub={`${number(k.vehiclesWithGps, 0)} vehicles with GPS data`} to={`/gps?${s()}`} />
            <HeroTile icon="fuel" tint="#eb6834" label="Fuel & energy spend" value={wholeRupees(k.totalFuelExpense)}
                      sub="All fuel bills in the period" to={`/fuel?${s()}`} />
            <HeroTile icon="rupee" tint="#137a3a" label="Fuel cost per km"
                      value={k.costPerKm.fuelCostPerKm === null ? "—" : rupees(k.costPerKm.fuelCostPerKm)}
                      sub={k.costPerKm.fuelPlusMaintenanceCostPerKm === null ? `${k.costPerKm.vehiclesIncluded} vehicles with km and fuel`
                        : `${rupees(k.costPerKm.fuelPlusMaintenanceCostPerKm)} / km with maintenance`}
                      to={`/reports?report=fuel-cost-per-km&${s()}`} />
            <HeroTile icon="wrench" tint="#5b3fc4" label="Maintenance spend" value={wholeRupees(k.maintenanceExpense)}
                      sub="Service and repair bills" to={`/maintenance?${s()}`} />
          </div>

          <div className="dash-row">
            <section className="card fleet-card">
              <div className="card-head">
                <div><h2><Icon name="car" /> Fleet status</h2><div className="card-sub">Vehicles registered today</div></div>
                <Link className="link-btn small" to={`/vehicles?${vehicleS()}`}>All vehicles →</Link>
              </div>
              <div className="fleet-total"><span>{number(v.total, 0)}</span> vehicles</div>
              <div className="status-bar" role="img" aria-label={segments.map((g) => `${g.label} ${g.n}`).join(", ")}>
                {segments.filter((g) => g.n > 0).map((g) => (
                  <span key={g.key} className={g.cls} style={{ flexGrow: g.n }} title={`${g.label}: ${number(g.n, 0)}`} />
                ))}
              </div>
              <div className="status-legend">
                {segments.map((g) => (
                  <Link key={g.key} to={`/vehicles?${vehicleS({ status: g.key })}`} className="status-legend-item">
                    <span className={`dot ${g.cls}`} />
                    <span className="status-legend-label">{g.label}</span>
                    <strong>{number(g.n, 0)}</strong>
                    <span className="muted small">{v.total ? `${Math.round((g.n / v.total) * 100)}%` : ""}</span>
                  </Link>
                ))}
              </div>
            </section>

            <section className="card attention-card">
              <div className="card-head"><div><h2><Icon name="alert" /> Needs attention</h2><div className="card-sub">Click a line to see the vehicles</div></div></div>
              <div className="attention-list">
                <AttentionItem icon="service" tone="bad" label="Service overdue" hint="Past the km or the date" count={k.serviceOverdue}
                               to={`/service-due?${vehicleS({ due: "OVERDUE" })}`} />
                <AttentionItem icon="service" tone="warn" label="Service due soon" hint="Within 500 km or 15 days" count={k.serviceDueSoon}
                               to={`/service-due?${vehicleS({ due: "DUE_SOON" })}`} />
                <AttentionItem icon="file" tone="bad" label="Documents expired" hint="Insurance, PUC, fitness, permit…" count={docs.data?.expired?.length ?? 0}
                               to="/documents?state=EXPIRED" />
                <AttentionItem icon="file" tone="warn" label="Documents expiring soon" hint="Renew within the reminder window" count={docs.data?.expiring?.length ?? 0}
                               to="/documents?state=DUE_SOON" />
                <AttentionItem icon="pin" tone="warn" label="Active vehicles without GPS data" hint="Not counted as zero km" count={k.vehiclesMissingGps}
                               to="/alerts?type=NO_GPS_REPORT&status=ACTIVE" />
                <AttentionItem icon="fuel" tone="warn" label="Km but no fuel recorded" hint="Fuel bills may be missing" count={k.vehiclesMissingFuel}
                               to="/alerts?type=GPS_NO_FUEL&status=ACTIVE" />
                <AttentionItem icon="trend" tone="bad" label="Mileage below expected" hint="Check driving, fuel or the vehicle" count={k.vehiclesDeterioratingMileage}
                               to={`/reports?report=low-efficiency-vehicles&${s()}`} />
              </div>
            </section>
          </div>

          <h2 className="section-title">Fuel and mileage</h2>
          <div className="fuel-cards">
            {FUELS.map((e) => {
              const f = k.fuelByEnergy[e];
              const m = k.fleetMileage.find((x) => x.energyType === e);
              if (!f?.quantity && !m) return null;
              return (
                <Link key={e} className="kpi fuel-card" to={`/fuel?${s({ energyType: e })}`}>
                  <div className="fuel-card-head"><span className="dot" style={{ background: FUEL_COLORS[e] }} />{FUEL_LABEL[e]}</div>
                  <div className="kpi-value">{m?.mileage ? `${number(m.mileage, 2)}` : "—"} <span className="kpi-unit">{EFFICIENCY_UNIT[e]}</span></div>
                  <div className="kpi-sub">{m ? `Average mileage · ${m.vehicles} vehicles` : "Not enough refuelling data for mileage"}</div>
                  <div className="fuel-card-foot">
                    <span>{number(f?.quantity ?? 0, 0)} {QUANTITY_UNIT[e]}</span>
                    <span>{wholeRupees(f?.expense ?? 0)}</span>
                    <span>{f?.weightedUnitPrice ? `${rupees(f.weightedUnitPrice)}/${QUANTITY_UNIT[e]}` : ""}</span>
                  </div>
                </Link>
              );
            })}
          </div>
        </>
      )}

      {charts.data && (
        <div className="grid grid-2">
          <ChartCard title="Kilometres per month" sub="GPS daily km, current records">
            <ResponsiveContainer>
              <BarChart data={charts.data.monthlyKm.map((r) => ({ ...r, month: month(r.month), km: Number(r.km) }))} barCategoryGap="28%">
                <CartesianGrid {...grid} />
                <XAxis dataKey="month" {...axis} />
                <YAxis {...axis} width={56} tickFormatter={(x) => number(x, 0)} />
                <Tooltip {...tooltip} formatter={(x: number) => [`${number(x, 0)} km`, "Kilometres"]} />
                <Bar dataKey="km" fill={SERIES_1} name="Kilometres" {...bar} />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>
          <ChartCard title="Fuel & energy spend per month" sub="Stacked by fuel">
            <ResponsiveContainer>
              <BarChart data={fuelChart} barCategoryGap="28%">
                <CartesianGrid {...grid} />
                <XAxis dataKey="month" {...axis} />
                <YAxis {...axis} width={56} tickFormatter={(x) => `₹${number(x / 1000, 0)}k`} />
                <Tooltip {...tooltip} formatter={(x: number, n: string) => [rupees(x), FUEL_LABEL[n] ?? n]} />
                <Legend {...legend} formatter={(n: string) => FUEL_LABEL[n] ?? n} />
                {energies.map((e, i) => (
                  <Bar key={e} dataKey={e} stackId="fuel" fill={FUEL_COLORS[e]} stroke="#fff" strokeWidth={1}
                       {...(i === energies.length - 1 ? bar : { maxBarSize: bar.maxBarSize })} />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>
          <ChartCard title="Mileage per month" sub="Weighted km per kg / litre / kWh, by fuel">
            <ResponsiveContainer>
              <LineChart data={mileageChart}>
                <CartesianGrid {...grid} />
                <XAxis dataKey="month" {...axis} />
                <YAxis {...axis} width={44} />
                <Tooltip {...tooltip} formatter={(x: number, n: string) => [`${number(x, 2)} ${EFFICIENCY_UNIT[n] ?? ""}`, FUEL_LABEL[n] ?? n]} />
                <Legend {...legend} formatter={(n: string) => FUEL_LABEL[n] ?? n} />
                {mileageEnergies.map((e) => (
                  <Line key={e} dataKey={e} stroke={FUEL_COLORS[e]} strokeWidth={2} dot={{ r: 4, strokeWidth: 2, fill: "#fff" }} activeDot={{ r: 5 }} connectNulls />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </ChartCard>
          <ChartCard title="Maintenance spend per month" sub="Service and repair bills">
            <ResponsiveContainer>
              <BarChart data={charts.data.maintenanceExpenditure.map((r) => ({ ...r, month: month(r.month), amount: Number(r.amount) }))} barCategoryGap="28%">
                <CartesianGrid {...grid} />
                <XAxis dataKey="month" {...axis} />
                <YAxis {...axis} width={56} tickFormatter={(x) => `₹${number(x / 1000, 0)}k`} />
                <Tooltip {...tooltip} formatter={(x: number) => [rupees(x), "Maintenance"]} />
                <Bar dataKey="amount" fill={SERIES_1} name="Maintenance" {...bar} />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>
          <ChartCard title="Vehicle utilisation" sub="Share of vehicle-days with GPS km">
            <ResponsiveContainer>
              <LineChart data={charts.data.utilisation.map((r) => ({ ...r, month: month(r.month), utilisationPct: Number(r.utilisationPct) }))}>
                <CartesianGrid {...grid} />
                <XAxis dataKey="month" {...axis} />
                <YAxis {...axis} width={44} domain={[0, 100]} unit="%" />
                <Tooltip {...tooltip} formatter={(x: number) => [`${number(x, 1)}%`, "Utilisation"]} />
                <Line dataKey="utilisationPct" stroke={SERIES_1} strokeWidth={2} dot={{ r: 4, strokeWidth: 2, fill: "#fff" }} name="Utilisation" />
              </LineChart>
            </ResponsiveContainer>
          </ChartCard>
          {k && (
            <section className="card note-card">
              <div className="card-head"><div><h2><Icon name="check" /> How these figures are calculated</h2></div></div>
              <ul className="note-list">
                <li>Mileage is weighted: total km ÷ total fuel, never an average of vehicles.</li>
                <li>Cost per km uses only vehicles with both km and fuel ({k.costPerKm.vehiclesIncluded} in this period).</li>
                <li>Missing GPS or fuel data is shown under <em>Needs attention</em>, never counted as zero.</li>
                <li className="muted">{k.costPerKm.basis}</li>
              </ul>
            </section>
          )}
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
