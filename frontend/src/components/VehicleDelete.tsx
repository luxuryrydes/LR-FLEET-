import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { api } from "../lib/api";
import { can, useAuth } from "../lib/auth";
import { ErrorBox, Field, Modal } from "./ui";

/** Query keys of every page that lists vehicles; refreshed after a delete or restore. */
const VEHICLE_QUERIES = ["vehicles", "doc-matrix", "service-status", "monthly-mileage", "monthly-mileage-trend", "compliance", "kpis", "charts", "lookups"];

export interface Picked {
  id: number;
  registrationNumber: string;
}

/** Ticked vehicles on a dashboard (kept across pages and filters until cleared). */
export function useVehicleSelection() {
  const [picked, setPicked] = useState<Map<number, string>>(new Map());
  const toggle = useCallback((v: Picked) => setPicked((m) => {
    const n = new Map(m);
    if (n.has(v.id)) n.delete(v.id);
    else n.set(v.id, v.registrationNumber);
    return n;
  }), []);
  const setAll = useCallback((rows: Picked[], on: boolean) => setPicked((m) => {
    const n = new Map(m);
    rows.forEach((v) => (on ? n.set(v.id, v.registrationNumber) : n.delete(v.id)));
    return n;
  }), []);
  const clear = useCallback(() => setPicked(new Map()), []);
  return { picked, has: (id: number) => picked.has(id), toggle, setAll, clear };
}

export type Selection = ReturnType<typeof useVehicleSelection>;

/** Header checkbox: ticks or clears every vehicle shown. */
export function SelectAll({ sel, rows }: { sel: Selection; rows: Picked[] }) {
  const all = rows.length > 0 && rows.every((r) => sel.has(r.id));
  return <input type="checkbox" className="row-check" checked={all} aria-label="Select all shown vehicles"
                onChange={(e) => sel.setAll(rows, e.target.checked)} onClick={(e) => e.stopPropagation()} />;
}

export function SelectOne({ sel, v }: { sel: Selection; v: Picked }) {
  return <input type="checkbox" className="row-check" checked={sel.has(v.id)} aria-label={`Select ${v.registrationNumber}`}
                onChange={() => sel.toggle(v)} onClick={(e) => e.stopPropagation()} />;
}

type Action = "delete" | "restore" | "purge";

/**
 * Bar shown above a dashboard while vehicles are ticked, plus the confirm dialog. Managers can delete and restore;
 * permanent removal is admin only and only for deleted vehicles without records.
 */
export function VehicleDeleteBar({ sel, deletedView = false }: { sel: Selection; deletedView?: boolean }) {
  const { user } = useAuth();
  const [action, setAction] = useState<Action | null>(null);
  if (!can(user, "manage") || sel.picked.size === 0) return null;
  return (
    <>
      <div className="select-bar" role="region" aria-label="Selected vehicles">
        <strong>{sel.picked.size} vehicle{sel.picked.size === 1 ? "" : "s"} selected</strong>
        <span className="muted small">{[...sel.picked.values()].slice(0, 6).join(", ")}{sel.picked.size > 6 ? " …" : ""}</span>
        <span className="spacer" />
        {deletedView ? <>
          <button className="btn" onClick={() => setAction("restore")}>Restore selected</button>
          {can(user, "admin") && <button className="btn btn-danger" onClick={() => setAction("purge")}>Delete permanently</button>}
        </> : <button className="btn btn-danger" onClick={() => setAction("delete")}>Delete selected</button>}
        <button className="btn" onClick={sel.clear}>Clear</button>
      </div>
      {action && <DeleteVehiclesModal action={action} vehicles={[...sel.picked].map(([id, registrationNumber]) => ({ id, registrationNumber }))}
                                      onClose={() => setAction(null)} onDone={() => { setAction(null); sel.clear(); }} />}
    </>
  );
}

/** Row button: delete just this vehicle. */
export function DeleteVehicleButton({ v }: { v: Picked }) {
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  if (!can(user, "manage")) return null;
  return (
    <>
      <button className="icon-btn danger" title={`Delete ${v.registrationNumber}`} aria-label={`Delete ${v.registrationNumber}`}
              onClick={(e) => { e.stopPropagation(); setOpen(true); }}>🗑</button>
      {open && <DeleteVehiclesModal action="delete" vehicles={[v]} onClose={() => setOpen(false)} onDone={() => setOpen(false)} />}
    </>
  );
}

export function DeleteVehiclesModal({ action, vehicles, onClose, onDone }: { action: Action; vehicles: Picked[]; onClose: () => void; onDone: () => void }) {
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<string[] | null>(null);
  const n = vehicles.length;
  const what = n === 1 ? vehicles[0].registrationNumber : `${n} vehicles`;
  const title = action === "delete" ? `Delete ${what}` : action === "restore" ? `Restore ${what}` : `Delete ${what} permanently`;

  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const ids = vehicles.map((v) => v.id);
      const res = await api.post<Record<string, unknown>>(`/api/vehicles/${action}`, { ids, reason: action === "delete" ? reason.trim() : null });
      VEHICLE_QUERIES.forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
      const left = ((res.skipped ?? res.kept ?? []) as string[]);
      if (left.length) {
        setResult(left);
        setBusy(false);
      } else {
        onDone();
      }
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  return (
    <Modal title={title} onClose={result ? onDone : onClose} footer={result ? <button className="btn btn-primary" onClick={onDone}>Close</button> : <>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className={`btn ${action === "restore" ? "btn-primary" : "btn-danger"}`} disabled={busy || (action === "delete" && !reason.trim())} onClick={go}>
        {busy ? "Working…" : action === "delete" ? `Delete ${n === 1 ? "vehicle" : `${n} vehicles`}` : action === "restore" ? "Restore" : "Delete permanently"}
      </button>
    </>}>
      <ErrorBox error={error} />
      {result ? (
        <>
          <p>Done. These were not changed:</p>
          <ul className="msg-list">{result.map((r) => <li key={r} className="msg-warning">{r}</li>)}</ul>
        </>
      ) : (
        <>
          {n > 1 && <p className="muted small">{vehicles.slice(0, 20).map((v) => v.registrationNumber).join(", ")}{n > 20 ? ` and ${n - 20} more` : ""}</p>}
          {action === "delete" && <>
            <p>The vehicle{n === 1 ? "" : "s"} will disappear from every list, dashboard, report, import and alert. Fuel, GPS km, service and
              document history is kept, and you can restore {n === 1 ? "it" : "them"} from Vehicles → Status: Deleted.</p>
            <Field label="Reason" required><input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. added by mistake, duplicate, left the fleet" autoFocus /></Field>
          </>}
          {action === "restore" && <p>The vehicle{n === 1 ? "" : "s"} come back with the status they had before deletion.</p>}
          {action === "purge" && <p><strong>This cannot be undone.</strong> Only deleted vehicles without any fuel, GPS, service, document or odometer
            records are removed; vehicles with history stay deleted so their records are kept.</p>}
        </>
      )}
    </Modal>
  );
}
