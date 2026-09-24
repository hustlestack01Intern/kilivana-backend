# Kilivana clients

Three client applications built against the `kilivana-backend` API (`/api/v1`).

## Layout

| App | Stack | Audience |
|---|---|---|
| `admin/` | React 18 + Vite (web) | Administrators: dashboard, users, products/orders/logistics/payments/disputes moderation, reports |
| `mobile/` | React Native (Expo) | Main app: buyers, farmers, suppliers, inspectors (login/register, marketplace, orders, profile) |
| `driver/` | React Native (Expo) | Drivers: available jobs, accept/decline, GPS tracking updates, proof of delivery, history |

## Configure the API URL

- **admin/** — dev server proxies `/api` to `http://localhost:8080` (override with `VITE_API_URL`).
- **mobile/** & **driver/** — set `"extra": { "apiUrl": "https://your-api-host" }` in `app.json`.

## Run

```bash
# Admin portal
cd client/admin
npm install
npm run dev            # http://localhost:5173

# Main app (Expo)
cd client/mobile
npm install
npm start              # then press i / a / scan QR with Expo Go

# Driver app (Expo)
cd client/driver
npm install
npm start
```

## Conventions

- Both RN apps share an identical `src/api/client.js` (axios with access-token header, one-shot refresh on 401, JSON error unwrapping) — keep the copy in sync when changing it.
- JWT access + refresh tokens persist in `AsyncStorage` (`kilivana.access` / `kilivana.refresh`) on mobile, `localStorage` on web.
- All list calls use the backend's Spring `Page` shape (`page`/`size`; response `content`, `totalElements`, `totalPages`).

## Auth notes

- First admin is created with the backend bootstrap key — see the root `README.md`. Log in to `/admin` with that account.
- Driver registration is done through the main app (role = DRIVER); drivers then sign in from the driver app.