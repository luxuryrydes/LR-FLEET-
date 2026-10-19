import { useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { can, useAuth, type Permission } from "../lib/auth";

const NAV: { to: string; label: string; icon: string; perm?: Permission; section?: string }[] = [
  { to: "/", label: "Dashboard", icon: "◧", section: "Overview" },
  { to: "/alerts", label: "Alerts", icon: "⚑" },
  { to: "/vehicles", label: "Vehicles", icon: "▣", section: "Fleet" },
  { to: "/gps", label: "GPS kilometres", icon: "⌖" },
  { to: "/fuel", label: "Fuel & energy", icon: "⛽" },
  { to: "/maintenance", label: "Maintenance", icon: "⚙" },
  { to: "/service-due", label: "Service due", icon: "⏱" },
  { to: "/documents", label: "Compliance", icon: "▤" },
  { to: "/imports", label: "Imports", icon: "⇪", perm: "write", section: "Data" },
  { to: "/reports", label: "Reports", icon: "▦" },
  { to: "/masters", label: "Drivers & vendors", icon: "☰" },
  { to: "/users", label: "Users & roles", icon: "☺", perm: "admin", section: "Admin" },
  { to: "/settings", label: "Settings", icon: "⚒", perm: "read" },
  { to: "/audit", label: "Audit log", icon: "⌸", perm: "manage" },
];

export function Layout() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  return (
    <div className={`shell ${open ? "nav-open" : ""}`}>
      <aside className="sidebar">
        <div className="brand">
          <img src="/favicon.svg" alt="" width={28} height={28} />
          <div>
            <strong>LR Fleet</strong>
            <span>LR Taxi Services Pvt Ltd</span>
          </div>
        </div>
        <nav onClick={() => setOpen(false)}>
          {NAV.filter((n) => !n.perm || can(user, n.perm)).map((n) => (
            <div key={n.to}>
              {n.section && <div className="nav-section">{n.section}</div>}
              <NavLink to={n.to} end={n.to === "/"} className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}>
                <span className="nav-icon" aria-hidden="true">{n.icon}</span>
                {n.label}
              </NavLink>
            </div>
          ))}
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          <button className="btn-icon menu-btn" onClick={() => setOpen(!open)} aria-label="Menu">☰</button>
          <div className="spacer" />
          <div className="user-chip">
            <span className="avatar">{user?.fullName?.charAt(0) ?? "?"}</span>
            <div>
              <div>{user?.fullName}</div>
              <div className="muted small">{user?.roles.join(", ")}</div>
            </div>
          </div>
          <button className="btn btn-small" onClick={logout}>Sign out</button>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
