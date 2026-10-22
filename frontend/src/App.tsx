import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { Spinner } from "./components/ui";
import { can, useAuth, type Permission } from "./lib/auth";
import { LoginPage } from "./pages/Login";
import { DashboardPage } from "./pages/Dashboard";
import { MonthlyMileagePage } from "./pages/MonthlyMileage";
import { VehiclesPage } from "./pages/Vehicles";
import { VehicleFormPage } from "./pages/VehicleForm";
import { VehicleDetailPage } from "./pages/VehicleDetail";
import { GpsPage } from "./pages/Gps";
import { FuelPage } from "./pages/Fuel";
import { ImportsPage } from "./pages/Imports";
import { ImportWizardPage } from "./pages/ImportWizard";
import { MaintenancePage } from "./pages/Maintenance";
import { ServiceDuePage } from "./pages/ServiceDue";
import { DocumentsPage } from "./pages/Documents";
import { AlertsPage } from "./pages/Alerts";
import { AlertDetailPage } from "./pages/AlertDetail";
import { ReportsPage } from "./pages/Reports";
import { MastersPage } from "./pages/Masters";
import { UsersPage } from "./pages/Users";
import { SettingsPage } from "./pages/Settings";
import { AuditPage } from "./pages/Audit";
import type { ReactElement } from "react";

function Guard({ perm, element }: { perm: Permission; element: ReactElement }) {
  const { user } = useAuth();
  return can(user, perm) ? element : <div className="alert alert-error">You do not have permission to open this page.</div>;
}

export function App() {
  const { user, loading } = useAuth();
  if (loading) return <Spinner text="Signing in…" />;
  if (!user) {
    return (
      <Routes>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    );
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<DashboardPage />} />
        <Route path="monthly-mileage" element={<MonthlyMileagePage />} />
        <Route path="vehicles" element={<VehiclesPage />} />
        <Route path="vehicles/new" element={<Guard perm="write" element={<VehicleFormPage />} />} />
        <Route path="vehicles/:id" element={<VehicleDetailPage />} />
        <Route path="vehicles/:id/edit" element={<Guard perm="write" element={<VehicleFormPage />} />} />
        <Route path="gps" element={<GpsPage />} />
        <Route path="fuel" element={<FuelPage />} />
        <Route path="imports" element={<Guard perm="write" element={<ImportsPage />} />} />
        <Route path="imports/new" element={<Guard perm="write" element={<ImportWizardPage />} />} />
        <Route path="imports/:id" element={<Guard perm="write" element={<ImportWizardPage />} />} />
        <Route path="maintenance" element={<MaintenancePage />} />
        <Route path="service-due" element={<ServiceDuePage />} />
        <Route path="documents" element={<DocumentsPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="alerts/:id" element={<AlertDetailPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="masters" element={<MastersPage />} />
        <Route path="users" element={<Guard perm="admin" element={<UsersPage />} />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="audit" element={<Guard perm="manage" element={<AuditPage />} />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
