import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, type Row } from "../lib/api";
import type { Role } from "../lib/auth";
import { dateTime } from "../lib/format";
import { DataTable } from "../components/DataTable";
import { Badge, ErrorBox, Field, Modal, PageHeader } from "../components/ui";

const ROLES: { role: Role; text: string }[] = [
  { role: "ADMIN", text: "Everything, including users and settings" },
  { role: "MANAGER", text: "All data changes, replacements, voids, alert resolution" },
  { role: "OPERATOR", text: "Data entry and imports" },
  { role: "VIEWER", text: "Read-only dashboards and reports" },
];

export function UsersPage() {
  const [editing, setEditing] = useState<Row | null>(null);
  const r = useQuery({ queryKey: ["users"], queryFn: () => api.get<Row[]>("/api/users") });
  return (
    <>
      <PageHeader title="Users & roles" subtitle="Permissions are enforced by the server on every request."
                  actions={<button className="btn btn-primary" onClick={() => setEditing({})}>+ Add user</button>} />
      <ErrorBox error={r.error} />
      <DataTable rows={r.data} loading={r.isLoading} onRowClick={setEditing} columns={[
        { key: "fullName", header: "Name" }, { key: "email", header: "Email" },
        { key: "roles", header: "Roles", render: (u) => (u.roles as string[]).map((x) => <Badge key={x} value="HEADERS_READY" text={x} />) },
        { key: "isActive", header: "Status", render: (u) => <Badge value={u.isActive ? "ACTIVE" : "INACTIVE"} /> },
        { key: "lastLoginAt", header: "Last sign-in", render: (u) => dateTime(u.lastLoginAt) },
      ]} />
      {editing && <UserModal user={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function UserModal({ user, onClose }: { user: Row; onClose: () => void }) {
  const qc = useQueryClient();
  const isNew = !user.id;
  const [f, setF] = useState({ email: user.email ?? "", fullName: user.fullName ?? "", password: "", active: user.isActive ?? true,
    roles: (user.roles as Role[] | undefined) ?? ["OPERATOR"] });
  const [error, setError] = useState<unknown>(null);
  const toggle = (r: Role) => setF({ ...f, roles: f.roles.includes(r) ? f.roles.filter((x) => x !== r) : [...f.roles, r] });
  const save = async () => {
    setError(null);
    try {
      if (isNew) await api.post("/api/users", { email: f.email, fullName: f.fullName, password: f.password, roles: f.roles });
      else {
        await api.put(`/api/users/${user.id}`, { fullName: f.fullName, active: f.active, roles: f.roles });
        if (f.password) await api.post(`/api/users/${user.id}/reset-password`, { password: f.password });
      }
      qc.invalidateQueries({ queryKey: ["users"] });
      onClose();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Modal title={isNew ? "Add user" : `Edit ${user.fullName}`} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn btn-primary" disabled={f.roles.length === 0 || (isNew && f.password.length < 10)} onClick={save}>Save</button>
    </>}>
      <ErrorBox error={error} />
      <div className="form-grid">
        <Field label="Email" required><input type="email" value={f.email} disabled={!isNew} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
        <Field label="Full name" required><input value={f.fullName} onChange={(e) => setF({ ...f, fullName: e.target.value })} /></Field>
        <Field label={isNew ? "Password" : "New password (optional)"} required={isNew} hint="At least 10 characters with letters and digits">
          <input type="password" autoComplete="new-password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} />
        </Field>
        {!isNew && (
          <Field label="Status">
            <select value={f.active ? "true" : "false"} onChange={(e) => setF({ ...f, active: e.target.value === "true" })} aria-label="Status">
              <option value="true">Active</option><option value="false">Deactivated</option>
            </select>
          </Field>
        )}
      </div>
      <h3 style={{ fontSize: 14 }}>Roles</h3>
      {ROLES.map((r) => (
        <label key={r.role} style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 6 }}>
          <input type="checkbox" checked={f.roles.includes(r.role)} onChange={() => toggle(r.role)} />
          <strong style={{ width: 90 }}>{r.role}</strong> <span className="muted">{r.text}</span>
        </label>
      ))}
    </Modal>
  );
}
