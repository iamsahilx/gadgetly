# Lab implementation checklist

Updated 27 November 2026 (Asia/Kolkata). This records the scope of the Java lab build and
the evidence for this change. The demo uses only seeded/synthetic accounts and the dummy
payment provider.

## Implemented

- [x] Review the storefront, API, database, payment boundary and admin workflow before editing.
- [x] Scope payment initiate, verify and status to the order owner; verify the stored provider
  reference and prevent duplicate attempts.
- [x] Keep a failed order payable by another dummy method; let customers cancel pending orders
  and restore reserved stock. Accept COD orders while leaving payment due on delivery.
- [x] Lock order, cart and stock rows for concurrent checkout/payment/cancellation; validate
  product variants, coupon usability/quota and nonnegative prices. Release unused coupon
  quota on cancellation and reject manual confirmation of unpaid orders.
- [x] Show parent-category products, hide inactive products from the public API, preserve
  product/order history, and let admins manage hidden products without erasing variants.
- [x] Keep guest-cart items until they are transferred, clear private query data on account
  changes, and recover pending checkout after navigation, reload or tab closure.
- [x] Remove unverified contact/tax details from the demo UI and configuration.
- [x] Add GitHub Actions for backend, frontend and container builds.

## Verification

- [x] Java 21 `mvn -B verify`: 35 tests passed and the executable JAR built.
- [x] Node 20 `npm ci`, `npm test` (7 passed) and `npm run build` (TypeScript/Vite).
- [x] Disposable PostgreSQL 16 startup with Flyway V1/V2 and API smoke: authentication,
  catalog, cart, checkout, dummy payment success/decline/retry, cancellation, cross-user
  denial and admin visibility.
- [x] Browser smoke: guest cart → login → checkout → failed card → UPI retry → order history;
  admin products, pending-order resume/reload, a 390px mobile catalog, and visible retry
  messages when the API is unavailable.
- [x] Final local Maven/Node checks and `git diff --check`.
- [x] Final live Postgres run: customer admin access returned 403; simultaneous checkout and
  payment requests produced one success and one 400 without duplicate rows or stock changes;
  a one-use coupon allowed one concurrent redemption, and cancellation released its quota.
  Admin confirmation of an unpaid pending order returned 409.
- [x] GitHub CI on PR #2: backend, frontend and Docker Compose image builds passed.

## Outside this lab demo

Real payment settlement, password-reset delivery, shipping and invoice generation require
provider integrations. A real payment provider also needs durable attempt records,
idempotency, signed callbacks and reconciliation. Seeded passwords/JWT secret and placeholder
images are demo-only.

Shop contact and tax details must come from the shop before publication. The production
dependency audit reports two moderate React Router advisories; the full audit
also reports six development-tool advisories. Their fixes require major-version migrations,
which are outside this lab change. Docker was unavailable on this Mac; GitHub CI built
both application images from the Compose configuration.
