import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, ApiError, type Row } from "../lib/api";
import { can, useAuth } from "../lib/auth";
import { date, number, todayIso } from "../lib/format";
import { QuickAddModal, type QuickKind } from "../components/QuickAdd";
import { useLookups } from "../lib/hooks";
import { normalizeRegistration } from "../lib/registration";
import { Card, ErrorBox, Field, Notice, PageHeader, Select, Spinner, codes } from "../components/ui";

export interface VehicleDraft {
  registrationNumber: string;
  duplicateRegistrationReason: string;
  make: string;
  model: string;
  modelYear: string;
  fuelType: string;
  categoryCode: string;
  ownershipType: string;
  vendorId: string;
  currentDriverId: string;
  currentDepartmentId: string;
  customerName: string;
  status: string;
  expectedMileage: string;
  serviceIntervalKm: string;
  serviceIntervalMonths: string;
  tankCapacity: string;
  batteryCapacityKwh: string;
  purchaseDate: string;
  purchaseCost: string;
  chassisNumber: string;
  engineNumber: string;
  gpsDeviceName: string;
  remarks: string;
  openingOdometerKm: string;
  openingOdometerDate: string;
  changeReason: string;
  effectiveDate: string;
  operatorType: string;
  gpsKmTracked: boolean;
  registrationClass: string;
}

const EMPTY: VehicleDraft = {
  registrationNumber: "", duplicateRegistrationReason: "", make: "", model: "", modelYear: "", fuelType: "", categoryCode: "",
  ownershipType: "OWNED", vendorId: "", currentDriverId: "", currentDepartmentId: "", customerName: "", status: "ACTIVE",
  expectedMileage: "", serviceIntervalKm: "", serviceIntervalMonths: "", tankCapacity: "", batteryCapacityKwh: "", purchaseDate: "",
  purchaseCost: "", chassisNumber: "", engineNumber: "", gpsDeviceName: "", remarks: "", openingOdometerKm: "", openingOdometerDate: "",
  changeReason: "", effectiveDate: "", operatorType: "OWN_DRIVER", gpsKmTracked: true, registrationClass: "",
};

/** Client-side checks for immediate feedback. The server repeats every check and is authoritative. */
export function validateVehicle(d: VehicleDraft): Record<string, string> {
  const e: Record<string, string> = {};
  const reg = normalizeRegistration(d.registrationNumber);
  if (!reg) e.registrationNumber = "Registration number is required";
  else if (reg.length < 4) e.registrationNumber = "Registration number must contain at least 4 letters/digits";
  if (!d.make.trim()) e.make = "Make is required";
  if (!d.model.trim()) e.model = "Model is required";
  if (!d.fuelType) e.fuelType = "Choose a fuel type";
  if (!d.categoryCode) e.categoryCode = "Choose a category";
  if (d.modelYear && (!/^\d{4}$/.test(d.modelYear) || +d.modelYear < 1980 || +d.modelYear > 2100)) e.modelYear = "Enter a year between 1980 and 2100";
  for (const k of ["expectedMileage", "tankCapacity", "batteryCapacityKwh"] as const) {
    if (d[k] && !(Number(d[k]) > 0)) e[k] = "Must be a number greater than zero";
  }
  for (const k of ["purchaseCost", "openingOdometerKm"] as const) {
    if (d[k] && !(Number(d[k]) >= 0)) e[k] = "Must be zero or more";
  }
  if (d.serviceIntervalKm && !(Number.isInteger(+d.serviceIntervalKm) && +d.serviceIntervalKm > 0)) e.serviceIntervalKm = "Whole kilometres greater than zero";
  if (d.serviceIntervalMonths && !(Number.isInteger(+d.serviceIntervalMonths) && +d.serviceIntervalMonths > 0)) e.serviceIntervalMonths = "Whole months greater than zero";
  return e;
}

function toPayload(d: VehicleDraft) {
  const n = (v: string) => (v.trim() === "" ? null : v.trim());
  const id = (v: string) => (v === "" ? null : Number(v));
  return {
    registrationNumber: d.registrationNumber, duplicateRegistrationReason: n(d.duplicateRegistrationReason), make: d.make.trim(),
    model: d.model.trim(), modelYear: d.modelYear ? Number(d.modelYear) : null, fuelType: d.fuelType, categoryCode: d.categoryCode,
    ownershipType: d.ownershipType, vendorId: id(d.vendorId), currentDriverId: id(d.currentDriverId),
    currentDepartmentId: id(d.currentDepartmentId), customerName: n(d.customerName), status: d.status,
    expectedMileage: n(d.expectedMileage), serviceIntervalKm: d.serviceIntervalKm ? Number(d.serviceIntervalKm) : null,
    serviceIntervalMonths: d.serviceIntervalMonths ? Number(d.serviceIntervalMonths) : null, tankCapacity: n(d.tankCapacity),
    batteryCapacityKwh: n(d.batteryCapacityKwh), purchaseDate: n(d.purchaseDate), purchaseCost: n(d.purchaseCost),
    chassisNumber: n(d.chassisNumber), engineNumber: n(d.engineNumber), gpsDeviceName: n(d.gpsDeviceName), remarks: n(d.remarks),
    openingOdometerKm: n(d.openingOdometerKm), openingOdometerDate: n(d.openingOdometerDate), changeReason: n(d.changeReason), effectiveDate: n(d.effectiveDate),
    operatorType: d.operatorType || "OWN_DRIVER", gpsKmTracked: d.gpsKmTracked, registrationClass: n(d.registrationClass),
  };
}

function fromVehicle(v: Row): VehicleDraft {
  const s = (x: unknown) => (x === null || x === undefined ? "" : String(x));
  return {
    ...EMPTY,
    registrationNumber: s(v.registrationNumber), duplicateRegistrationReason: s(v.duplicateRegistrationReason), make: s(v.make),
    model: s(v.model), modelYear: s(v.modelYear), fuelType: s(v.fuelType), categoryCode: s(v.categoryCode), ownershipType: s(v.ownershipType),
    vendorId: s(v.vendorId), currentDriverId: s(v.currentDriverId), currentDepartmentId: s(v.currentDepartmentId),
    customerName: s(v.customerName), status: s(v.status), expectedMileage: s(v.expectedMileage), serviceIntervalKm: s(v.serviceIntervalKm),
    serviceIntervalMonths: s(v.serviceIntervalMonths), tankCapacity: s(v.tankCapacity), batteryCapacityKwh: s(v.batteryCapacityKwh),
    purchaseDate: s(v.purchaseDate), purchaseCost: s(v.purchaseCost), chassisNumber: s(v.chassisNumber), engineNumber: s(v.engineNumber),
    gpsDeviceName: s(v.gpsDeviceName), remarks: s(v.remarks), operatorType: s(v.operatorType) || "OWN_DRIVER",
    gpsKmTracked: Boolean(v.gpsKmTracked), registrationClass: s(v.registrationClass), effectiveDate: todayIso(),
  };
}

export function VehicleForm({ initial, editing, onSubmit, busy, serverError, heldGps }: {
  initial: VehicleDraft;
  editing: boolean;
  onSubmit: (payload: ReturnType<typeof toPayload>) => void;
  busy?: boolean;
  serverError?: unknown;
  /** GPS km kept aside while the vehicle was not with an LR driver: {days, km, firstDate, lastDate} */
  heldGps?: Row;
}) {
  const [d, setD] = useState<VehicleDraft>(initial);
  const [quick, setQuick] = useState<QuickKind | null>(null);
  const { user } = useAuth();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState(false);
  const { data: lk } = useLookups();
  useEffect(() => setD(initial), [initial]);

  const set = (k: keyof VehicleDraft) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const next = { ...d, [k]: e.target.value };
    setD(next);
    if (touched) setErrors(validateVehicle(next));
  };
  const setV = (k: keyof VehicleDraft) => (v: string) => set(k)({ target: { value: v } } as React.ChangeEvent<HTMLInputElement>);

  // Field errors from the server (bean validation) are shown next to the fields too.
  const serverFields: Record<string, string> = {};
  if (serverError instanceof ApiError) for (const f of serverError.fieldErrors) serverFields[f.field] = f.message;
  const err = (k: string) => errors[k] ?? serverFields[k];

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setTouched(true);
    const v = validateVehicle(d);
    setErrors(v);
    if (Object.keys(v).length === 0) onSubmit(toPayload(d));
  };
  const normalized = normalizeRegistration(d.registrationNumber);
  // What changed in the assignment (edit only): drives the effective date and the GPS km note.
  const assignmentChanged = editing && (["vendorId", "currentDriverId", "currentDepartmentId", "operatorType", "ownershipType", "status"] as const)
    .some((k) => d[k] !== initial[k]);
  const trackingChanged = editing && d.gpsKmTracked !== initial.gpsKmTracked;
  // Picking a driver for a vendor vehicle (or a vendor without a driver) switches the driver type and GPS km with it.
  const pickDriver = (v: string) => setD((x) => ({ ...x, currentDriverId: v,
    ...(v && x.operatorType === "VENDOR" ? { operatorType: "OWN_DRIVER", gpsKmTracked: true, vendorId: "" } : {}) }));
  const pickVendor = (v: string) => setD((x) => ({ ...x, vendorId: v,
    ...(v && x.operatorType === "OWN_DRIVER" ? { operatorType: "VENDOR", gpsKmTracked: false, currentDriverId: "", ownershipType: "VENDOR_ATTACHED" } : {}) }));
  const vehicleVendors = (lk?.vendors ?? []).filter((v) => v.vendorType === "VEHICLE_VENDOR" || v.vendorType === "OTHER" || String(v.id) === d.vendorId);
  const addLink = (kind: QuickKind, label: string) => (
    <button type="button" className="link-btn small" onClick={() => setQuick(kind)}>+ {label}</button>
  );

  return (
    <form onSubmit={submit} noValidate>
      <ErrorBox error={serverError} />
      <Card title="Identity">
        <div className="form-grid">
          <Field label="Registration number" required error={err("registrationNumber")}
                 hint={normalized && normalized !== d.registrationNumber ? `Will be saved as ${normalized}` : undefined}>
            <input value={d.registrationNumber} onChange={set("registrationNumber")} placeholder="DL 1C AB 1234" />
          </Field>
          <Field label="Make" required error={err("make")}><input value={d.make} onChange={set("make")} /></Field>
          <Field label="Model" required error={err("model")}><input value={d.model} onChange={set("model")} /></Field>
          <Field label="Model year" error={err("modelYear")}><input inputMode="numeric" value={d.modelYear} onChange={set("modelYear")} /></Field>
          <Field label="Fuel type" required error={err("fuelType")}>
            <Select value={d.fuelType} onChange={setV("fuelType")} options={codes(lk?.fuelTypes)} placeholder="Choose…" aria-label="Fuel type" />
          </Field>
          <Field label="Category" required error={err("categoryCode")}>
            <Select value={d.categoryCode} onChange={setV("categoryCode")} placeholder="Choose…" aria-label="Category"
                    options={(lk?.vehicleCategories ?? []).map((c) => ({ value: c.code, label: c.name }))} />
          </Field>
          <Field label="Chassis number"><input value={d.chassisNumber} onChange={set("chassisNumber")} /></Field>
          <Field label="Engine number"><input value={d.engineNumber} onChange={set("engineNumber")} /></Field>
          <Field label="GPS device name" hint="Saved as an alias so imports match this vehicle"><input value={d.gpsDeviceName} onChange={set("gpsDeviceName")} /></Field>
          <Field label="Duplicate registration reason" hint="Only for a documented exception; leave blank normally">
            <input value={d.duplicateRegistrationReason} onChange={set("duplicateRegistrationReason")} />
          </Field>
        </div>
      </Card>
      <Card title="Assignment & status">
        <div className="form-grid">
          <Field label="Ownership" required>
            <Select value={d.ownershipType} onChange={setV("ownershipType")} options={codes(lk?.ownershipTypes)} aria-label="Ownership" />
          </Field>
          <Field label="Vendor" hint={addLink("vendor", "New vendor")}>
            <Select value={d.vendorId} onChange={pickVendor} placeholder="None" aria-label="Vendor"
                    options={vehicleVendors.map((v) => ({ value: String(v.id), label: v.name }))} />
          </Field>
          {/* A vendor vehicle has no LR driver: the box comes back when the driver type is changed to an LR driver. */}
          {d.operatorType !== "VENDOR" && (
            <Field label="Driver" hint={addLink("driver", "New driver")}>
              <Select value={d.currentDriverId} onChange={pickDriver} placeholder="Unassigned" aria-label="Driver"
                      options={(lk?.drivers ?? []).map((v) => ({ value: String(v.id), label: `${v.fullName}${v.phone ? ` · ${v.phone}` : ""}` }))} />
            </Field>
          )}
          <Field label="Department" hint={can(user, "manage") ? addLink("department", "New department") : undefined}>
            <Select value={d.currentDepartmentId} onChange={setV("currentDepartmentId")} placeholder="Unassigned" aria-label="Department"
                    options={(lk?.departments ?? []).map((v) => ({ value: String(v.id), label: v.name }))} />
          </Field>
          <Field label="Driver type" hint="Who drives the vehicle">
            <Select value={d.operatorType} aria-label="Driver type"
                    onChange={(v) => setD({ ...d, operatorType: v, gpsKmTracked: v === "OWN_DRIVER", ...(v === "VENDOR" ? { currentDriverId: "" } : {}) })}
                    options={codes(lk?.operatorTypes ?? ["OWN_DRIVER", "VENDOR", "GOVT_DRIVER", "OFFICER", "PERSONAL", "NONE"])} />
          </Field>
          <Field label="GPS km" hint="Only vehicles driven by LR's own drivers should be imported">
            <label style={{ display: "flex", gap: 8, alignItems: "center", fontWeight: 400 }}>
              <input type="checkbox" checked={d.gpsKmTracked} onChange={(e) => setD({ ...d, gpsKmTracked: e.target.checked })} />
              Import GPS km for this vehicle
            </label>
          </Field>
          <Field label="Private / Commercial">
            <Select value={d.registrationClass} onChange={setV("registrationClass")} placeholder="—" aria-label="Private or commercial"
                    options={[{ value: "PRIVATE", label: "Private" }, { value: "COMMERCIAL", label: "Commercial" }]} />
          </Field>
          <Field label="Customer"><input value={d.customerName} onChange={set("customerName")} /></Field>
          <Field label="Status">
            <Select value={d.status} onChange={setV("status")} options={codes(lk?.vehicleStatuses)} aria-label="Status" />
          </Field>
          {editing && (
            <Field label="Reason for change" hint="Recorded in the assignment history"><input value={d.changeReason} onChange={set("changeReason")} /></Field>
          )}
          {editing && (assignmentChanged || trackingChanged) && (
            <Field label="Change applies from" required hint="Driver, vendor and department history start on this date">
              <input type="date" value={d.effectiveDate} max={todayIso()} onChange={set("effectiveDate")} aria-label="Change applies from" />
            </Field>
          )}
        </div>
        {trackingChanged && (
          <Notice kind="info">
            {d.gpsKmTracked
              ? <>Now an LR driver: GPS km from {date(d.effectiveDate)} will be counted.
                {Number(heldGps?.days) > 0 ? <> {number(heldGps?.days, 0)} days ({number(heldGps?.km, 0)} km, {date(heldGps?.firstDate)} – {date(heldGps?.lastDate)}) were
                  imported earlier while it was not counted; the ones from {date(d.effectiveDate)} are added automatically.</> : " Later GPS imports include it."}</>
              : <>No longer an LR driver: GPS km from {date(d.effectiveDate)} are taken out of mileage and kept aside, so they come back
                automatically if the vehicle returns to an LR driver. Fuel expense keeps being recorded for the vehicle.</>}
          </Notice>
        )}
      </Card>
      <Card title="Mileage, service & purchase">
        <div className="form-grid">
          <Field label="Expected mileage" error={err("expectedMileage")} hint="km/L, km/kg or km/kWh for the primary fuel">
            <input inputMode="decimal" value={d.expectedMileage} onChange={set("expectedMileage")} />
          </Field>
          <Field label="Service interval (km)" error={err("serviceIntervalKm")} hint="Blank = category or 10,000 km default">
            <input inputMode="numeric" value={d.serviceIntervalKm} onChange={set("serviceIntervalKm")} />
          </Field>
          <Field label="Service interval (months)" error={err("serviceIntervalMonths")}><input inputMode="numeric" value={d.serviceIntervalMonths} onChange={set("serviceIntervalMonths")} /></Field>
          <Field label="Tank capacity (L)" error={err("tankCapacity")}><input inputMode="decimal" value={d.tankCapacity} onChange={set("tankCapacity")} /></Field>
          <Field label="Battery capacity (kWh)" error={err("batteryCapacityKwh")}><input inputMode="decimal" value={d.batteryCapacityKwh} onChange={set("batteryCapacityKwh")} /></Field>
          <Field label="Purchase date"><input type="date" value={d.purchaseDate} onChange={set("purchaseDate")} /></Field>
          <Field label="Purchase cost (₹)" error={err("purchaseCost")}><input inputMode="decimal" value={d.purchaseCost} onChange={set("purchaseCost")} /></Field>
          {!editing && (
            <>
              <Field label="Current odometer (km)" error={err("openingOdometerKm")} hint="Becomes the first trusted odometer baseline">
                <input inputMode="decimal" value={d.openingOdometerKm} onChange={set("openingOdometerKm")} />
              </Field>
              <Field label="Odometer reading date"><input type="date" value={d.openingOdometerDate} onChange={set("openingOdometerDate")} /></Field>
            </>
          )}
        </div>
        <Field label="Remarks"><textarea value={d.remarks} onChange={set("remarks")} /></Field>
      </Card>
      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "Saving…" : editing ? "Save changes" : "Create vehicle"}</button>
      </div>
      {quick && <QuickAddModal kind={quick} onClose={() => setQuick(null)} onCreated={(id) => {
        const k = quick;
        setQuick(null);
        if (k === "driver") pickDriver(String(id));
        else if (k === "vendor") pickVendor(String(id));
        else setD((x) => ({ ...x, currentDepartmentId: String(id) }));
      }} />}
    </form>
  );
}

export function VehicleFormPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const editing = Boolean(id);
  const existing = useQuery({ queryKey: ["vehicle", id], queryFn: () => api.get<Row>(`/api/vehicles/${id}`), enabled: editing });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [initial, setInitial] = useState<VehicleDraft>(EMPTY);
  const [gpsResult, setGpsResult] = useState<Row | null>(null);
  useEffect(() => {
    if (existing.data) setInitial(fromVehicle(existing.data));
  }, [existing.data]);

  const save = async (payload: ReturnType<typeof toPayload>) => {
    setBusy(true);
    setError(null);
    try {
      if (editing) {
        const res = await api.put<Row>(`/api/vehicles/${id}`, payload);
        await qc.invalidateQueries({ queryKey: ["vehicle", id] });
        ["gps", "monthly-mileage", "service-status", "kpis", "charts", "lookups"].forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
        if (res?.gpsKm) setGpsResult(res.gpsKm as Row);
        else navigate(`/vehicles/${id}`);
      } else {
        const res = await api.post<{ id: number }>("/api/vehicles", payload);
        navigate(`/vehicles/${res.id}`);
      }
      await qc.invalidateQueries({ queryKey: ["vehicles"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (editing && existing.isLoading) return <Spinner />;
  return (
    <>
      <PageHeader title={editing ? `Edit ${existing.data?.registrationNumber ?? "vehicle"}` : "Add vehicle"} />
      {gpsResult ? (
        <Card title="Saved">
          <Notice kind="success">
            {gpsResult.direction === "ADDED"
              ? `${number(gpsResult.days, 0)} GPS days (${number(gpsResult.km, 0)} km) from ${date(gpsResult.from)} were added from earlier imports and now count in mileage.`
              : `${number(gpsResult.days, 0)} GPS days (${number(gpsResult.km, 0)} km) from ${date(gpsResult.from)} were taken out of mileage and kept aside.`}
          </Notice>
          <div className="form-actions"><button className="btn btn-primary" onClick={() => navigate(`/vehicles/${id}`)}>Open vehicle</button></div>
        </Card>
      ) : (
        <VehicleForm initial={initial} editing={editing} onSubmit={save} busy={busy} serverError={error} heldGps={existing.data?.heldGps as Row | undefined} />
      )}
    </>
  );
}
