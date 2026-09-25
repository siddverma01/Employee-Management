# Employee Management Application

A production-grade full-stack Employee Management Application for managing employee records, attendance, leave, compensatory off (swap off), holidays, events, Excel imports, dashboards, and notifications.

## Stack

### Backend

- **Java 17** + **Spring Boot 3.3.5**
- **Spring Security** with stateless JWT
- **Spring Data JPA** + **PostgreSQL** (H2 for tests)
- **Flyway** migrations
- **Apache POI** Excel import
- **Springdoc OpenAPI**

### Frontend

- **React 18** + **TypeScript** + **Vite 6**
- **TanStack Query** data fetching + caching
- **React Hook Form** + **Zod** validation
- **Tailwind CSS 3** utility classes
- **Recharts** admin charts
- **Lucide React** icons

### DevOps

- **Docker + Docker Compose** for one-command startup
- **PostgreSQL 16** database

---

## Quick start

### 1. Clone & prepare

```bash
git clone <this-repo> empmgmt
cd empmgmt
cp .env.example .env
```

Edit `.env` and set a strong value for `APP_JWT_SECRET`.

### 2. Start with Docker (recommended)

```bash
docker compose --env-file .env up -d --build
```

- Frontend: **http://localhost** (default port 80)
- Backend API: **http://localhost:8080**
- Swagger UI: **http://localhost:8080/swagger-ui.html**

On first boot (with `APP_SEED_ENABLED=true` in dev) the backend seeds:
- Admin (login-only, no employee profile): `admin@emplmgt.com` / `Admin@123`
- 23 HPE Voice team employees (`employeeCode` = Emp ID, e.g. `masher.choudhary-ext@hpe.com`) / `Welcome@123`

### Local development (without Docker)

#### Backend

Requires **Java 17** and **Maven 3.9+**.

```bash
cd backend
mvn spring-boot:run
```

This starts the backend with the `dev` profile (H2-compatible Postgres on localhost:5432 by default; set `SPRING_PROFILES_ACTIVE` to change).

#### Frontend

Requires **Node.js 20+**.

```bash
cd frontend
npm install
npm run dev
```

Vite proxies `/api` to `localhost:8080`.

---

## Architecture overview

### Backend packages (`com.emplmgt`)

| Layer | Description |
|---|---|
| `controller` | REST controllers grouped by domain. Admin endpoints under `/api/admin/**` require role `ADMIN`. |
| `service` | Business logic: leave overlap/balance checks, swap-off approval + CO credit, Excel import pipeline, calendar aggregation, dashboard stats, scheduled notifications. |
| `dto` | Request/response records kept in domain-grouped classes (`LeaveDtos`, `EmployeeDtos`, etc.). |
| `entity` | JPA entities mapped to normalized Postgres schema. |
| `repository` | Spring Data repositories with custom JPQL queries and pagination support. |
| `security` | JWT service, auth filter, password user details service, security configuration. |
| `util` | `AppClock` (timezone-aware clock), `LeaveDaysCalculator` (configurable weekend/holiday exclusion), `JsonUtil`. |
| `config` | OpenAPI config, CORS/web config, dev `DataSeeder`. |

### Frontend structure (`frontend/src`)

| Folder | Description |
|---|---|
| `api/` | Axios client with Bearer interceptor + all endpoint wrappers (`authApi`, `adminApi`, `leaveApi`, etc.). |
| `components/ui` | Reusable primitives (Button, Input, Select, Modal, StatusBadge, Avatar, Pagination, etc.). |
| `components/layout` | `MainLayout` — desktop sidebar + mobile drawer + top bar + notification badge. |
| `pages/employee` | Employee self-service pages (dashboard, profile, leaves, swap-offs, calendar, today, holidays, notifications, attendance). |
| `pages/admin` | Admin pages (dashboard with charts, employees CRUD, leave/swap-off approvals, Excel import wizard, holidays/events, audit logs, attendance management). |
| `validations` | Zod schemas for login, leave, swap-off, employee, holiday, event, and rejection forms. |
| `hooks` | `useAuth` (login state + token management), `useNotifications`, `useDebouncedValue`. |

### Business rules

| Rule | Behaviour |
|---|---|
| Weekend/holiday exclusion | Configurable via `application.leave.*` (`weekly-offs`, `exclude-holidays`). |
| Overlapping leaves | PENDING or APPROVED leaves on the same dates are rejected. |
| Attendance conflicts | Cannot apply for leave on a day already marked WFO/WFH/COMP_OFF. |
| Self-approval prevention | An admin cannot approve or reject their own leave/swap-off request. |
| COMP_OFF balance | Dynamic: base allocation + count of approved swap-off credits for the year. |
| HPE holiday taken (rule A) | Employee is marked `HPEH` in the roster for the actual holiday date; no entitlement is created. |
| HPE holiday worked (rule B) | Employee earns one HPEH entitlement (`hpe_entitlements`, unique per employee + holiday) usable within 3 months of the **original** holiday date; it expires afterwards and can never be used twice. Master HPE holiday definitions are stored once and filtered by the employee's location (`ALL` or `PUNE_MUMBAI`). |
| HPE earning is automatic | A daily scan (`application.hpe.earn-cron`) plus `POST /admin/hpe-holidays/:id/entitlements/sync` award entitlements to every **active** employee recorded as having *worked* on an HPE holiday date. The check reuses the existing attendance sources in order: roster status cell (`attendance_records`: `WFO/WFH/SW WK/WK WRK/WDT/HD/TR` = worked, `HPEH/WO/PL/SL/...` = not worked) → daily `attendance` record → configured week off. A date with **no** recorded status is `UNKNOWN` and never counts as work, so sync is idempotent and never awards by default. |
| HPE holidays on the calendar | The existing `GET /api/calendar` includes active `HPE_HOLIDAY` definitions, filtered by the caller's location (`ALL` / `PUNE_MUMBAI` / …) and hidden when inactive. They render as normal holiday chips with a small `HPEH` badge and open as **HPE Holiday** in the details modal (`.cal-hpeh-tag`, coloured by `--attendance-hpeh-*` so light/dark stay in sync). Personal earned entitlements are never calendar events. |
| Excel import | Upload → suggested column mapping → preview with VALID/INVALID/DUPLICATE rows → commit (imports only VALID rows, never overwrites existing attendance). |
| Notifications | Scheduled hour broadcast upcoming holidays/birthdays; approval requests notify users. |

### API surface

All endpoints are prefixed with `/api`.

| Group | Examples |
|---|---|
| Auth | `POST /auth/login`, `GET /auth/me`, `POST /auth/logout` |
| Employees | `GET /employees/me`, `GET /employees/birthdays/upcoming` |
| Admin Employees | `GET /admin/employees`, `POST /admin/employees`, `PATCH /admin/employees/:id/status` |
| Leaves | `POST /leaves`, `GET /leaves`, `GET /leaves/balances`, `POST /leaves/:id/cancel` |
| Admin Leaves | `GET /admin/leaves`, `POST /admin/leaves/:id/approve`, `POST /admin/leaves/:id/reject` |
| Swap Offs | `POST /swap-offs`, `GET /swap-offs`, `POST /swap-offs/:id/cancel` |
| Admin Swap Offs | `GET /admin/swap-offs`, `POST /admin/swap-offs/:id/approve`, `POST /admin/swap-offs/:id/reject` |
| Attendance | `GET /attendance/me` |
| Admin Attendance | `GET /admin/attendance`, `POST /admin/attendance` |
| Today | `GET /today/status` |
| Calendar | `GET /calendar` |
| Holidays / Events | `GET /holidays`, `GET /holidays/upcoming`, `GET /events` |
| Admin Holidays / Events | CRUD under `/admin/holidays`, `/admin/events` |
| HPE Holidays | `GET /hpe-holidays` (master HPE holidays applicable to your location), `GET /hpe-holidays/entitlements`, `GET /hpe-holidays/entitlements/all`, `GET /hpe-holidays/entitlements/summary`, `POST /hpe-holidays/entitlements`, `POST /hpe-holidays/entitlements/use` |
| Admin HPE Holidays | `GET/POST /admin/hpe-holidays`, `PUT/DELETE /admin/hpe-holidays/:id`, `POST /admin/hpe-holidays/:id/entitlements/sync`, `POST /admin/hpe-holidays/entitlements/sync` |
| Notifications | `GET /notifications`, `GET /notifications/unread-count`, `POST /notifications/:id/read`, `POST /notifications/read-all` |
| Admin Excel | `POST /admin/excel/upload`, `POST /admin/excel/:id/mapping`, `POST /admin/excel/:id/commit`, `GET /admin/excel/imports` |
| Dashboards | `GET /dashboard/me`, `GET /admin/dashboard/summary`, `GET /admin/dashboard/charts` |
| Audit | `GET /admin/audit-logs` |

---

## Testing

### Backend

```bash
cd backend
mvn test
```

Covers: `LeaveDaysCalculator` rules, `LeaveService` apply/cancel/approve/reject validation, `AuthService` login flows, `AttendanceService` today-status aggregation.

### Frontend

```bash
cd frontend
npm run test
```

Covers: validation schemas, date/leave-day utils, Login form rendering and submit behaviour, core UI primitives.

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/emp_mgmt` | JDBC URL (override in prod) |
| `DB_USERNAME` / `DB_PASSWORD` | `emp_mgmt` | Database credentials |
| `APP_JWT_SECRET` | *(required)* | HMAC secret for JWT signing |
| `APP_JWT_EXPIRATION_MS` | `86400000` | Token lifetime (default 24 h) |
| `APP_TIMEZONE` | `UTC` | Application timezone for "today" calculations |
| `APP_SEED_ENABLED` | `true` (dev) | Seed admin + Voice team employees on startup |
| `APP_IMPORT_DIR` | `./data/uploads` | Uploaded Excel storage directory |
| `APP_EXCLUDE_WEEKENDS` | `true` | Whether weekend days count against leave balance |
| `APP_EXCLUDE_HOLIDAYS` | `true` | Whether public holidays count against leave balance |
| `APP_WEEKLY_OFFS` | `6,7` | ISO day numbers for weekly off (6=Sat, 7=Sun) |
| `APP_HPE_EXPIRE_CRON` | `0 30 1 * * *` | Daily sweep that expires overdue HPEH entitlements |
| `APP_HPE_EARN_CRON` | `0 15 2 * * *` | Daily scan that awards entitlements for HPE holidays that were worked |
| `FRONTEND_PORT` | `80` | Nginx port mapped to host |
| `BACKEND_PORT` | `8080` | Backend port mapped to host |

---

## Notes and assumptions

- **Weekend and holiday rules** are configurable; the default configuration assumes Saturdays and Sundays off.
- **Attendance deduplication**: An attendance row is uniquely constrained by `(employee_id, attendance_date)`. Excel imports and the system will never overwrite an existing record — duplicates are flagged in the import preview and silently skipped on commit.
- **COMP_OFF credits** are counted dynamically from approved swap-off requests; no stored counter is maintained.
- The application **does not have an audit log retention policy**; entries are appended indefinitely. Add a scheduled job or TTL in a production deployment.
- File uploads (profile pictures, Excel imports, leave attachments) are stored in `APP_IMPORT_DIR`; a production deployment should use cloud storage or a volume mount with a proper backup strategy.
- No SSO or external identity provider is integrated — authentication is internal JWT-based. For production, consider SAML/OIDC via Spring Security adapters.
- Time zone awareness: all date-only operations (today, calendar, holidays, leave calculations) use `AppClock`, which resolves `APP_TIMEZONE` at startup. Ensure this matches your organisation's operating timezone.