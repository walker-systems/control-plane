# Control Plane UI

React + TypeScript + Vite frontend for the Control Plane API.

## Stack

- Vite + React 19 + TypeScript
- Tailwind CSS (v4)
- React Router
- TanStack Query (data + polling)
- Zustand (auth state, persisted to localStorage)

## Dev

```bash
# From services/ui/
npm install
npm run dev
```

The dev server listens on `http://localhost:5173` and proxies `/api/*` to `http://localhost:8080` (the Spring API). Start the API separately before the UI, e.g. from `services/api/`:

```bash
EXECUTOR_ENABLED=true ../../mvnw spring-boot:run
```

## Build

```bash
npm run build   # emits dist/
npm run lint    # oxlint
```

## Layout

```
src/
  components/ui/  # small Tailwind primitives (Button, Input, Label)
  lib/            # api client, auth store, query client
  routes/         # page components + route guards
  App.tsx         # route tree
  main.tsx        # Providers + StrictMode + createRoot
```

## Phase

Phase C.1 (this scaffold): login → protected dashboard.
Phase C.2: jobs surface (dashboard tiles, list, detail, cancel/retry).
Phase C.3: schedules surface + create-job form.
