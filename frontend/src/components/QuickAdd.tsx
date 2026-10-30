import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../lib/api";
import { ErrorBox, Field, Modal } from "./ui";

export type QuickKind = "driver" | "vendor" | "department";

const TITLES: Record<QuickKind, string> = { driver: "New driver", vendor: "New vendor", department: "New department" };

/** Adds a driver, vehicle vendor or department without leaving the vehicle form; returns the new id. */
export function QuickAddModal({ kind, onClose, onCreated }: { kind: QuickKind; onClose: () => void; onCreated: (id: number) => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState({ name: "", phone: "", licenceNumber: "", licenceExpiry: "", contactPerson: "", code: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const n = (v: string) => (v.trim() === "" ? null : v.trim());
  const code = (f.code || f.name).toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 30);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = kind === "driver"
        ? await api.post<{ id: number }>("/api/drivers", { fullName: f.name.trim(), phone: n(f.phone), licenceNumber: n(f.licenceNumber),
            licenceExpiry: n(f.licenceExpiry), vendorId: null, remarks: null, active: true })
        : kind === "vendor"
        ? await api.post<{ id: number }>("/api/vendors", { name: f.name.trim(), vendorType: "VEHICLE_VENDOR", contactPerson: n(f.contactPerson),
            phone: n(f.phone), email: null, gstin: "", address: null, active: true })
        : await api.post<{ id: number }>("/api/departments", { code, name: f.name.trim(), active: true });
      await qc.invalidateQueries({ queryKey: ["lookups"] });
      onCreated(res.id);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  const phoneBad = f.phone !== "" && f.phone.replace(/\D/g, "").length < 10;
  return (
    <Modal title={TITLES[kind]} onClose={onClose} footer={<>
      <button className="btn" type="button" onClick={onClose}>Cancel</button>
      <button className="btn btn-primary" type="button" disabled={busy || !f.name.trim() || phoneBad} onClick={save}>{busy ? "Saving…" : "Add and select"}</button>
    </>}>
      <ErrorBox error={error} />
      <div className="form-grid">
        <Field label={kind === "driver" ? "Driver name" : kind === "vendor" ? "Vendor name" : "Department name"} required>
          <input value={f.name} onChange={set("name")} autoFocus aria-label="Name" />
        </Field>
        {kind !== "department" && (
          <Field label="Mobile" error={phoneBad ? "Enter a 10-digit mobile number" : undefined}>
            <input inputMode="tel" value={f.phone} onChange={set("phone")} aria-label="Mobile" />
          </Field>
        )}
        {kind === "driver" && <>
          <Field label="Licence number"><input value={f.licenceNumber} onChange={set("licenceNumber")} /></Field>
          <Field label="Licence expiry"><input type="date" value={f.licenceExpiry} onChange={set("licenceExpiry")} /></Field>
        </>}
        {kind === "vendor" && <Field label="Contact person"><input value={f.contactPerson} onChange={set("contactPerson")} /></Field>}
        {kind === "department" && (
          <Field label="Short code" hint={`Saved as ${code || "—"}`}><input value={f.code} onChange={set("code")} placeholder="e.g. MHA" /></Field>
        )}
      </div>
    </Modal>
  );
}
