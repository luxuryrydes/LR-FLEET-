import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { ReactNode } from "react";
import { FilterBar } from "../components/FilterBar";
import { ImportPreview } from "../components/ImportPreview";
import { defaultFilters } from "../lib/filters";
import { VehicleForm, validateVehicle, type VehicleDraft } from "../pages/VehicleForm";
import { MappingStep } from "../pages/ImportWizard";
import { DisposeModal } from "../pages/VehicleDetail";
import * as authModule from "../lib/auth";
import { Can } from "../lib/auth";

const lookups = {
  vehicleCategories: [{ code: "SEDAN", name: "Sedan" }], serviceCategories: [], departments: [{ id: 4, name: "Airport" }],
  vendors: [], drivers: [], fuelTypes: ["PETROL", "CNG"], energyTypes: [], ownershipTypes: ["OWNED"], vehicleStatuses: ["ACTIVE"], documentTypes: [],
};

function wrap(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  qc.setQueryData(["lookups"], lookups);
  return render(<QueryClientProvider client={qc}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}

describe("FilterBar", () => {
  it("applies the chosen filters", async () => {
    const onApply = vi.fn();
    wrap(<FilterBar value={{ ...defaultFilters(), from: "2026-10-01", to: "2026-10-19" }} onApply={onApply} />);
    await userEvent.selectOptions(screen.getByLabelText("Fuel type"), "CNG");
    await userEvent.selectOptions(screen.getByLabelText("Department"), "4");
    await userEvent.type(screen.getByLabelText("Vehicle number"), "DL1RT");
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ fuelType: "CNG", departmentId: "4", vehicle: "DL1RT", from: "2026-10-01" }));
  });

  it("refuses an inverted date range with a visible message", async () => {
    const onApply = vi.fn();
    wrap(<FilterBar value={{ ...defaultFilters(), from: "2026-10-19", to: "2026-10-01" }} onApply={onApply} />);
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("'From' date must be on or before 'To' date");
  });
});

describe("ImportPreview", () => {
  const rows = [
    { rowNumber: 2, status: "VALID", registrationNumber: "DL1RT1234", recordDate: "2026-10-01", parsed: { vehicleRaw: "DL1RT1234 (CNG)", km: "120.5" }, raw: {}, messages: [] },
    { rowNumber: 3, status: "DUPLICATE", registrationNumber: "DL1RT1234", recordDate: "2026-10-01", parsed: { vehicleRaw: "DL1RT1234 (CNG)", km: "120.5" }, raw: {},
      messages: [{ level: "info", code: "DUPLICATE_DAILY_TOTAL", field: null, message: "Repeated copy of the daily total in row 2; it will not be summed" }] },
    { rowNumber: 4, status: "REJECTED", registrationNumber: null, recordDate: null, parsed: null, raw: { "Device Name": "XX99" },
      messages: [{ level: "error", code: "UNMATCHED_VEHICLE", field: "vehicle", message: "Vehicle 'XX99' is not in the vehicle register" }] },
  ];

  it("shows every row's status and the rejection reason", () => {
    render(<ImportPreview rows={rows} importType="GPS_KM" />);
    const table = screen.getByRole("table", { name: "Import preview" });
    const body = within(table).getAllByRole("row").slice(1);
    expect(body).toHaveLength(3);
    expect(within(body[0]).getByText("Valid")).toBeInTheDocument();
    expect(within(body[0]).getByText("120.5")).toBeInTheDocument();
    expect(within(body[1]).getByText(/will not be summed/)).toBeInTheDocument();
    expect(within(body[2]).getByText("Rejected")).toBeInTheDocument();
    expect(within(body[2]).getByText(/not in the vehicle register/)).toHaveClass("msg-error");
  });

  it("renders an empty state", () => {
    render(<ImportPreview rows={[]} importType="FUEL" />);
    expect(screen.getByText("No rows in this view.")).toBeInTheDocument();
  });
});

describe("Import mapping step", () => {
  const batch = {
    id: 9, importType: "GPS_KM",
    sheetInfo: {
      targetFields: { GPS_KM: ["vehicle", "date", "km", "tripStart"] },
      sheets: [{ name: "Distance", headerRow: 4, headers: ["Device Name", "Start Time", "Distance (Kms)"],
        sample: [{ "Device Name": "DL1RT1234 (CNG)", "Start Time": "01-10-2026 08:00:00", "Distance (Kms)": 120.5 }],
        suggestedMapping: { GPS_KM: { vehicle: "Device Name", date: "Start Time" } } }],
    },
  };
  it("pre-fills suggested columns and blocks until required fields are mapped", async () => {
    wrap(<MappingStep b={batch} onDone={() => {}} />);
    expect(screen.getByLabelText("Column for Vehicle number / GPS device name")).toHaveValue("Device Name");
    const validate = screen.getByRole("button", { name: "Validate rows" });
    expect(validate).toBeDisabled();
    expect(screen.getAllByText("Required")).toHaveLength(1);
    await userEvent.selectOptions(screen.getByLabelText("Column for Distance (km)"), "Distance (Kms)");
    expect(validate).toBeEnabled();
    expect(screen.getByText("DL1RT1234 (CNG)")).toBeInTheDocument();
  });
});

describe("Vehicle form validation messages", () => {
  const empty: VehicleDraft = {
    registrationNumber: "", duplicateRegistrationReason: "", make: "", model: "", modelYear: "", fuelType: "", categoryCode: "", ownershipType: "OWNED",
    effectiveDate: "", vendorId: "", currentDriverId: "", currentDepartmentId: "", customerName: "", status: "ACTIVE", expectedMileage: "", serviceIntervalKm: "",
    serviceIntervalMonths: "", tankCapacity: "", batteryCapacityKwh: "", purchaseDate: "", purchaseCost: "", chassisNumber: "", engineNumber: "",
    gpsDeviceName: "", remarks: "", openingOdometerKm: "", openingOdometerDate: "", changeReason: "",
    operatorType: "OWN_DRIVER", gpsKmTracked: true, registrationClass: "",
  };

  it("validates required and numeric fields", () => {
    const e = validateVehicle({ ...empty, registrationNumber: "a-1", expectedMileage: "-3", modelYear: "1890", serviceIntervalKm: "10.5" });
    expect(e.registrationNumber).toMatch(/at least 4/);
    expect(e.make).toBe("Make is required");
    expect(e.fuelType).toBe("Choose a fuel type");
    expect(e.expectedMileage).toMatch(/greater than zero/);
    expect(e.modelYear).toMatch(/1980/);
    expect(e.serviceIntervalKm).toMatch(/Whole kilometres/);
    expect(validateVehicle({ ...empty, registrationNumber: "DL 1C AB 1234", make: "Maruti", model: "Dzire", fuelType: "CNG", categoryCode: "SEDAN" })).toEqual({});
  });

  it("shows messages on submit and does not call the API when invalid", async () => {
    const onSubmit = vi.fn();
    wrap(<authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles: ["OPERATOR"] }, loading: false, login: vi.fn(), logout: vi.fn() }}><VehicleForm initial={empty} editing={false} onSubmit={onSubmit} /></authModule.AuthContext.Provider>);
    await userEvent.click(screen.getByRole("button", { name: "Create vehicle" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText("Registration number is required")).toBeInTheDocument();
    expect(screen.getByText("Make is required")).toBeInTheDocument();
  });

  it("previews the normalised registration and submits a clean payload", async () => {
    const onSubmit = vi.fn();
    wrap(<authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles: ["OPERATOR"] }, loading: false, login: vi.fn(), logout: vi.fn() }}><VehicleForm initial={{ ...empty, make: "Maruti", model: "Dzire", fuelType: "CNG", categoryCode: "SEDAN" }} editing={false} onSubmit={onSubmit} /></authModule.AuthContext.Provider>);
    await userEvent.type(screen.getByPlaceholderText("DL 1C AB 1234"), "dl 1c-ab 1234");
    expect(screen.getByText("Will be saved as DL1CAB1234")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Create vehicle" }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ registrationNumber: "dl 1c-ab 1234", fuelType: "CNG", expectedMileage: null }));
  });
});

describe("Permission-gated controls", () => {
  const as = (roles: authModule.Role[], ui: ReactNode) => (
    <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles }, loading: false, login: vi.fn(), logout: vi.fn() }}>
      {ui}
    </authModule.AuthContext.Provider>
  );

  it("hides write controls from a viewer and shows them to an operator", () => {
    const { rerender } = render(as(["VIEWER"], <Can perm="write"><button>Add vehicle</button></Can>));
    expect(screen.queryByRole("button", { name: "Add vehicle" })).not.toBeInTheDocument();
    rerender(as(["OPERATOR"], <Can perm="write"><button>Add vehicle</button></Can>));
    expect(screen.getByRole("button", { name: "Add vehicle" })).toBeInTheDocument();
    rerender(as(["OPERATOR"], <Can perm="manage"><button>Replace records</button></Can>));
    expect(screen.queryByRole("button", { name: "Replace records" })).not.toBeInTheDocument();
    rerender(as(["MANAGER"], <Can perm="manage"><button>Replace records</button></Can>));
    expect(screen.getByRole("button", { name: "Replace records" })).toBeInTheDocument();
  });
});

describe("Vehicle master preview and disposal", () => {
  it("shows the derived status and GPS tracking for each master row", () => {
    const rows = [
      { rowNumber: 2, status: "VALID", parsed: { registration: "DL1AA0001", model: "Ciaz", driverName: "TEST", operatorLabel: "DRIVER",
        preview: { status: "ACTIVE", operatorType: "OWN_DRIVER", gpsKmTracked: true, make: "Maruti Suzuki", modelName: "Ciaz", fuel: "CNG_PETROL", existing: false } }, messages: [] },
      { rowNumber: 3, status: "VALID", parsed: { registration: "DL1AA0003", model: "Ciaz", driverName: null, operatorLabel: "WORKSHOP CH",
        preview: { status: "WORKSHOP", operatorType: "NONE", gpsKmTracked: false, make: "Maruti Suzuki", modelName: "Ciaz", fuel: "CNG_PETROL", existing: true } }, messages: [] },
    ];
    render(<ImportPreview rows={rows} importType="VEHICLE_MASTER" />);
    const body = within(screen.getByRole("table", { name: "Import preview" })).getAllByRole("row").slice(1);
    expect(within(body[0]).getByText("Active")).toBeInTheDocument();
    expect(within(body[0]).getByText("imported")).toBeInTheDocument();
    expect(within(body[0]).getByText("(new)")).toBeInTheDocument();
    expect(within(body[1]).getByText("Workshop")).toBeInTheDocument();
    expect(within(body[1]).getByText("not imported")).toBeInTheDocument();
    expect(within(body[1]).getByText("(update)")).toBeInTheDocument();
  });

  it("requires a dealer / buyer before a sale can be saved", async () => {
    wrap(<DisposeModal id="1" onClose={() => {}} onDone={() => {}} />);
    const save = screen.getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Sold to (dealer / buyer)"), "Maruti True Value, Gurugram");
    expect(save).toBeEnabled();
    await userEvent.selectOptions(screen.getByLabelText("Disposal type"), "SCRAPPED");
    expect(screen.getByLabelText("Scrap dealer")).toBeInTheDocument();
  });
});

describe("Monthly mileage calculator", () => {
  it("shows month fuel expense ÷ month km per vehicle and mixed fuel as km/kgL", async () => {
    const { api } = await import("../lib/api");
    const { MonthlyMileagePage } = await import("../pages/MonthlyMileage");
    const month = {
      month: "2026-10", totals: {
        vehicles: 2, vehiclesIncluded: 2, statusCounts: { OK: 1, MIXED_FUEL: 1 }, totalKm: 7200, totalFuelExpense: 28800.4,
        includedKm: 7200, includedFuelExpense: 28800.4, costPerKm: 4.0, mileageByEnergy: { CNG: { km: 4200, quantity: 180, mileage: 23.333, unit: "km/kg" } },
        mixedFuel: { km: 3000, quantity: 130, mileage: 23.077, unit: "km/kgL", vehicles: 1 },
      },
      rows: [
        { vehicleId: 1, registrationNumber: "DL1MM0001", vehicle: "Maruti Dzire", fuelType: "CNG", operatorType: "OWN_DRIVER", km: 4200, cngKg: 180,
          fuelExpense: 15800.4, costPerKm: 3.76, mileage: 23.333, mileageUnit: "km/kg", expectedMileage: 24, variancePct: -2.78, belowExpected: false, status: "OK" },
        { vehicleId: 2, registrationNumber: "DL1MM0002", vehicle: "Maruti Ciaz", fuelType: "CNG_PETROL", operatorType: "OWN_DRIVER", km: 3000, cngKg: 120,
          petrolL: 10, combinedQuantity: 130, fuelExpense: 13000, costPerKm: 4.33, mileage: 23.077, mileageUnit: "km/kgL", expectedMileage: null, variancePct: null, belowExpected: false, status: "MIXED_FUEL" },
      ],
    };
    const spy = vi.spyOn(api, "get").mockImplementation(async (path: string) => (path.endsWith("/trend") ? [] : month) as never);
    wrap(
      <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles: ["VIEWER"] }, loading: false, login: vi.fn(), logout: vi.fn() }}>
        <MonthlyMileagePage />
      </authModule.AuthContext.Provider>,
    );
    expect(await screen.findByText("₹4.00")).toBeInTheDocument();                  // fleet ₹/km
    expect(screen.getAllByText("23.33 km/kg")).toHaveLength(2);                  // fleet CNG card + vehicle row
    const table = screen.getByRole("table", { name: "Monthly mileage by vehicle" });
    expect(within(table).getByText("₹3.76")).toBeInTheDocument();
    expect(within(table).getByText("180 kg CNG + 10 L petrol".replace("180", "120"))).toBeInTheDocument();
    expect(screen.getAllByText("23.08 km/kgL")).toHaveLength(2);                 // mixed-fuel card + vehicle row: 3000 ÷ (120 kg + 10 L)
    expect(within(table).getByText("= 130 kgL")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: /Mixed fuel \(km\/kgL\)/ }));
    expect(within(table).queryByText("DL1MM0001")).not.toBeInTheDocument();
    expect(within(table).getByText("DL1MM0002")).toBeInTheDocument();
    spy.mockRestore();
  }, 30000);
});

describe("Vehicle documents dashboard", () => {
  const cell = (status: string, extra: Record<string, unknown> = {}) => ({ status, required: true, daysToExpiry: null, ...extra });
  const matrix = {
    types: [{ code: "INSURANCE", label: "Insurance" }, { code: "PUC", label: "Pollution (PUC)" }, { code: "FITNESS", label: "Fitness" }],
    reminderDays: 30, vehicles: 2,
    summary: {
      INSURANCE: { VALID: 1, DUE_SOON: 0, EXPIRED: 1, NOT_REQUIRED: 0, NO_EXPIRY: 0, MISSING: 0, OPTIONAL: 0, WITH_FILE: 1, NO_FILE: 1 },
      PUC: { VALID: 0, DUE_SOON: 1, EXPIRED: 0, NOT_REQUIRED: 1, NO_EXPIRY: 0, MISSING: 0, OPTIONAL: 0, WITH_FILE: 0, NO_FILE: 1 },
      FITNESS: { VALID: 0, DUE_SOON: 0, EXPIRED: 0, NOT_REQUIRED: 0, NO_EXPIRY: 0, MISSING: 1, OPTIONAL: 1, WITH_FILE: 0, NO_FILE: 0 },
    },
    rows: [
      { vehicleId: 1, registrationNumber: "DL52GD0247", model: "Tigor EV", registrationClass: "COMMERCIAL", documents: {
        INSURANCE: cell("VALID", { id: 11, expiryDate: "2027-09-13", fileId: 5, fileName: "policy.pdf", daysToExpiry: 325 }),
        PUC: cell("NOT_REQUIRED", { id: 12, notApplicable: true }),
        FITNESS: cell("MISSING") } },
      { vehicleId: 2, registrationNumber: "DL8CAR9335", model: "Ciaz", registrationClass: "PRIVATE", documents: {
        INSURANCE: cell("EXPIRED", { id: 21, expiryDate: "2026-10-01", daysToExpiry: -22 }),
        PUC: cell("DUE_SOON", { id: 22, expiryDate: "2026-11-05", daysToExpiry: 13 }),
        FITNESS: cell("OPTIONAL", { required: false }) } },
    ],
  };

  it("shows every vehicle's documents by colour, scans, and filters from a KPI card", async () => {
    const { api } = await import("../lib/api");
    const { DocumentMatrix } = await import("../pages/Documents");
    const spy = vi.spyOn(api, "get").mockImplementation(async () => matrix as never);
    wrap(
      <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles: ["OPERATOR"] }, loading: false, login: vi.fn(), logout: vi.fn() }}>
        <DocumentMatrix />
      </authModule.AuthContext.Provider>,
    );
    const table = await screen.findByRole("table", { name: "Vehicle documents" });
    expect(within(table).getByText("DL52GD0247")).toBeInTheDocument();
    expect(within(table).getByRole("button", { name: "DL52GD0247 Insurance: Valid" })).toHaveTextContent("13-09-2027");
    expect(within(table).getByRole("button", { name: "DL52GD0247 Pollution (PUC): Not required" })).toHaveTextContent("N/A");
    expect(within(table).getByRole("button", { name: "DL52GD0247 Fitness: Missing" })).toHaveTextContent("Missing");
    expect(within(table).getByRole("button", { name: "DL8CAR9335 Insurance: Expired" })).toHaveTextContent("22 d ago");
    expect(within(table).getByRole("button", { name: "DL8CAR9335 Pollution (PUC): Due soon" })).toHaveTextContent("13 d left");
    expect(within(table).getByTitle("Scan: policy.pdf")).toBeInTheDocument();
    expect(within(table).getAllByTitle("No scan uploaded")).toHaveLength(2);
    await userEvent.click(screen.getByRole("button", { name: "Insurance: show vehicles needing attention" }));
    expect(spy).toHaveBeenLastCalledWith("/api/documents/matrix", expect.objectContaining({ docType: "INSURANCE", state: "ATTENTION" }));
    spy.mockRestore();
  }, 30000);

  it("says plainly when the import worker is not running instead of loading forever", async () => {
    const { WorkerNotice } = await import("../pages/ImportWizard");
    wrap(<WorkerNotice w={{ online: false, lastSeen: null, queuedJobs: 1, wakeConfigured: false }} />);
    expect(screen.getByText("The import worker is not running")).toBeInTheDocument();
    expect(screen.getByText(/render-free.yaml/)).toBeInTheDocument();
  });
});

describe("Vehicle service portal", () => {
  const row = (reg: string, status: string, extra: Record<string, unknown> = {}) => ({
    vehicleId: reg.length, registrationNumber: reg, model: "Dzire", status, intervalKm: 10000, reasons: [], lastServiceId: null,
    serviceDateRecorded: false, lastServiceOdometer: null, currentOdometer: null, nextServiceOdometer: null, remainingKm: null,
    nextServiceDate: null, daysRemaining: null, lastServiceFileId: null, ...extra,
  });
  const rows = [
    row("DL1CAH7243", "DUE_SOON", { vehicleId: 1, lastServiceId: 11, serviceDateRecorded: true, lastServiceDate: "2026-09-25", lastServiceOdometer: 178024,
      daysSinceService: 28, currentOdometer: 187724, nextServiceOdometer: 188024, remainingKm: 300, nextServiceDate: "2027-03-25", daysRemaining: 153,
      lastServiceFileId: 7, lastServiceFileName: "jobcard.pdf" }),
    row("HR51CS9447", "MISSING_BASELINE", { vehicleId: 2, lastServiceId: 12, lastServiceOdometer: 132806, nextServiceOdometer: 142806 }),
    row("DL1CAK4856", "MISSING_SERVICE_RECORD", { vehicleId: 3 }),
  ];

  it("shows last and next service, scans and filters from the cards", async () => {
    const { api } = await import("../lib/api");
    const { ServiceDuePage } = await import("../pages/ServiceDue");
    const spy = vi.spyOn(api, "get").mockImplementation(async () => rows as never);
    wrap(
      <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles: ["OPERATOR"] }, loading: false, login: vi.fn(), logout: vi.fn() }}>
        <ServiceDuePage />
      </authModule.AuthContext.Provider>,
    );
    const table = await screen.findByRole("table", { name: "Vehicle service" });
    expect(within(table).getByText(/300 km left/)).toBeInTheDocument();
    expect(within(table).getByText("date not recorded")).toBeInTheDocument();
    expect(within(table).getByText(/jobcard\.pdf/)).toBeInTheDocument();
    expect(within(table).getByText("⤒ not uploaded")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Show Date not recorded" }));
    expect(within(table).queryByText("DL1CAH7243")).not.toBeInTheDocument();
    expect(within(table).getByText("HR51CS9447")).toBeInTheDocument();
    spy.mockRestore();
  }, 30000);
});


describe("Deleting vehicles", () => {
  const as = (roles: authModule.Role[], ui: ReactNode) => (
    <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles }, loading: false, login: vi.fn(), logout: vi.fn() }}>
      {ui}
    </authModule.AuthContext.Provider>
  );

  it("deletes several ticked vehicles with a reason, and hides the option from operators", async () => {
    const { api } = await import("../lib/api");
    const { SelectOne, VehicleDeleteBar, useVehicleSelection } = await import("../components/VehicleDelete");
    const post = vi.spyOn(api, "post").mockResolvedValue({ deleted: 2, skipped: [] } as never);
    function Harness() {
      const sel = useVehicleSelection();
      return <>
        <SelectOne sel={sel} v={{ id: 1, registrationNumber: "DL1AA0001" }} />
        <SelectOne sel={sel} v={{ id: 2, registrationNumber: "DL1AA0002" }} />
        <VehicleDeleteBar sel={sel} />
      </>;
    }
    const { unmount } = wrap(as(["MANAGER"], <Harness />));
    await userEvent.click(screen.getByLabelText("Select DL1AA0001"));
    await userEvent.click(screen.getByLabelText("Select DL1AA0002"));
    expect(screen.getByText("2 vehicles selected")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Delete selected" }));
    const confirm = screen.getByRole("button", { name: "Delete 2 vehicles" });
    expect(confirm).toBeDisabled();                                       // a reason is required
    await userEvent.type(screen.getByPlaceholderText(/added by mistake/), "Duplicate entries");
    await userEvent.click(confirm);
    expect(post).toHaveBeenCalledWith("/api/vehicles/delete", { ids: [1, 2], reason: "Duplicate entries" });
    post.mockRestore();
    unmount();

    wrap(as(["OPERATOR"], <Harness />));
    await userEvent.click(screen.getByLabelText("Select DL1AA0001"));
    expect(screen.queryByRole("button", { name: "Delete selected" })).not.toBeInTheDocument();
  });
});


describe("Changing a vehicle's driver or vendor", () => {
  const base: VehicleDraft = {
    registrationNumber: "CH5250", duplicateRegistrationReason: "", make: "Toyota", model: "Innova Crysta", modelYear: "2026", fuelType: "DIESEL",
    categoryCode: "SEDAN", ownershipType: "OWNED", effectiveDate: "2026-10-01", vendorId: "", currentDriverId: "1", currentDepartmentId: "4",
    customerName: "", status: "ACTIVE", expectedMileage: "", serviceIntervalKm: "", serviceIntervalMonths: "", tankCapacity: "", batteryCapacityKwh: "",
    purchaseDate: "", purchaseCost: "", chassisNumber: "", engineNumber: "", gpsDeviceName: "", remarks: "", openingOdometerKm: "",
    openingOdometerDate: "", changeReason: "", operatorType: "OWN_DRIVER", gpsKmTracked: true, registrationClass: "COMMERCIAL",
  };

  it("switches to vendor with GPS km set aside, from the chosen date, and offers adding a new vendor", async () => {
    const onSubmit = vi.fn();
    const qcLookups = { ...lookups, vendors: [{ id: 9, name: "BS Nayak", vendorType: "VEHICLE_VENDOR" }], drivers: [{ id: 1, fullName: "SANJAY JADU" }] };
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    qc.setQueryData(["lookups"], qcLookups);
    render(<QueryClientProvider client={qc}><MemoryRouter>
      <authModule.AuthContext.Provider value={{ user: { id: 1, email: "u@x", fullName: "U", roles: ["MANAGER"] }, loading: false, login: vi.fn(), logout: vi.fn() }}>
        <VehicleForm initial={base} editing onSubmit={onSubmit} heldGps={{ days: 0, km: 0 }} />
      </authModule.AuthContext.Provider>
    </MemoryRouter></QueryClientProvider>);
    expect(screen.getByRole("button", { name: "+ New vendor" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ New driver" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ New department" })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Vendor"), "9");
    expect(screen.getByLabelText("Driver type")).toHaveValue("VENDOR");
    expect(screen.getByText(/No longer an LR driver/)).toBeInTheDocument();
    expect(screen.getByLabelText("Change applies from")).toHaveValue("2026-10-01");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ vendorId: 9, currentDriverId: null, operatorType: "VENDOR", gpsKmTracked: false,
      ownershipType: "VENDOR_ATTACHED", effectiveDate: "2026-10-01" }));
  });
});
