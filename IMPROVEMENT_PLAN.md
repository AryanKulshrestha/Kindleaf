# Kindleaf — Audit & Improvement Plan

## Implementation status (2026-09-24)

**Done:**
- **Phase 0, all of it:** security lockdown in migration `008`, and the admin password removed from migrations `002`/`003`.
- **Phase 1:** 1.1–1.10, 1.12 and 1.13, plus deep links from 1.11.
- **Phase 2, all of it:** fake data, hardcoded badges and dead buttons removed.
- **Phase 3, mostly:**
  - Server-side pricing, a stats RPC and paging.
  - Order status events and the `category_id` foreign key.
  - Typed deep links and a role guard on admin routes.
  - Parallel bootstrap and the new `.env` names.
  - ViewModels for checkout and payment.
- **Phase 4, mostly:**
  - Tests: 74 database checks (`supabase/tests`) and JVM unit tests.
  - GitHub Actions CI.
  - Release build: R8, a signing config, backups off, `targetSdk` 36.
  - Dark-mode contrast, accessibility fixes, and confirmations on destructive actions.

**To go live:** follow [supabase/README.md](supabase/README.md). Apply migration 008, set the Razorpay secrets, deploy the 5 functions, add the webhook, and fill in Store settings.

**Not done yet (next steps):**
- **Push notifications (FCM, 1.11):** needs a Firebase project and `google-services.json`. In-app notifications and local tray notifications work.
- **Localization:** strings are still hardcoded English.
- **ViewModel migration:** only checkout and payment use ViewModels; the other screens still use the global stores.
- **Admin panel:** still inside the customer APK. It is guarded, and the database enforces permissions.
- **Money:** still whole rupees, not paise.
- **Integrations:** no crash reporting, analytics or shipping-aggregator integration.
- **Phase 5 growth features:** subscriptions, variants, guest browsing, referrals and so on.

_Audit date: 2026-09-24 · Scope: Android app (`app/`, ~13k lines Kotlin/Compose) + Supabase schema (`supabase/migrations/001–007`)_

## Summary

The customer journey (browse → cart → checkout → track → cancel) and the admin panel (products, categories, orders, customers, coupons, charges, reviews, broadcasts) are built end to end, and the project compiles. The UI is further along than the backend.

The project is **not production-ready yet**, for three reasons:

1. **Security holes in the database rules.**
   - Any logged-in customer can make themselves an admin.
   - Customers can set their own prices.
   - Customers can mint Leaf Points.
2. **Payments are not real.** "UPI" and "Card" are only labels; no payment ever happens.
3. **Many screens show fake or placeholder content.**
   - Sample reviews and sample coupons.
   - Hardcoded cart badges.
   - A search box that doesn't search.
   - A support form that sends nothing.
   - FAQs that promise features that don't exist.

The plan is ordered by risk: **Phase 0 must ship before any real customer uses the app.**

Effort key: **S** ≈ < 1 day · **M** ≈ 1–3 days · **L** ≈ 1–2 weeks

---

## What's already solid

- Row Level Security (RLS) is enabled on every table, and there is an `is_admin()` helper.
- Cancel and refund logic lives in `security definer` RPCs, which lock the row with `FOR UPDATE`. This is the right pattern; extend it to ordering.
- There is a trigger that keeps one default address per user, and profiles are created automatically on signup.
- Google sign-in uses a nonce, and the client secret is kept out of the APK.
- Delivery charges and per-event notification toggles are configurable by the admin.
- The build compiles cleanly (`:app:compileDebugKotlin` passes).

---

## Phase 0 — Critical security fixes (do first)

### 0.1 Privilege escalation: any user can become admin 🔴 — S
**Where:** the `profiles` policies in `001_kindleaf_core.sql` and `002_admin_and_commerce.sql`.

**Problem:** the "updatable by owner" policy lets users update *every* column of their own row. Any customer can run `update profiles set role='admin', leaf_points=999999 where id = auth.uid()` through the public API key. They then get full admin access through RLS.

**Fix:** one of these two:
- Column-level grants: `revoke update on public.profiles from authenticated; grant update (name, phone, avatar_initials, updated_at) on public.profiles to authenticated;`
- A `BEFORE UPDATE` trigger that rejects changes to `role`, `leaf_points` or `email` unless `is_admin()`.

Apply the same restriction to `INSERT`: force `role='customer'` and `leaf_points=0`.

### 0.2 Client-controlled prices and totals 🔴 — M
**Where:** `OrdersRepository.placeOrder`, `AppStateManager.placeOrder`, and the `orders`/`order_items` insert policies.

**Problem:** the app computes `subtotal`, `discount`, `delivery_fee`, `total` and every line `price`, then inserts them directly. Anyone can place an order for ₹1 or apply a 100% "discount". Users can also insert `order_items` into any of their own orders, including old ones.

**Fix:** a `place_order(p_address_id, p_items jsonb, p_coupon_code, p_payment_method, p_redeem_points)` RPC (`security definer`) that does all of this in **one transaction**:
- Reads prices from `items`.
- Checks each item is active and in stock.
- Validates the coupon, and computes charges from `charges`.
- Inserts the order and its lines, and decrements stock.
- Records coupon and points usage.
- Returns the order.

Then revoke direct `INSERT` on `orders` and `order_items` from `authenticated`. This also fixes the current non-atomic insert, where the order row can succeed and the line items fail.

### 0.3 Leaf Points and refunds for orders that were never paid 🔴 — S (guard) / L (real payments, see 1.1)
**Problem:** choosing "UPI" or "Card" creates an order with no payment. Cancelling it with `leaf_points` then credits `total × 10` points, and `money_refund` puts a refund for money never received into the admin queue.

**Fix now:**
- Add `payment_status` (`pending` / `paid` / `failed` / `refunded`) to `orders`.
- In `cancel_own_order` and `admin_cancel_order`, only allow a refund or points when `payment_status = 'paid'`.
- Until the gateway exists, hide UPI and Card at checkout (COD only).

### 0.4 Review forgery 🔴 — S
**Problem:** users can insert reviews with `is_verified = true`, any `author` name, and any `helpful_count`. Nothing limits them to one review per product.

**Fix:**
- A `BEFORE INSERT` trigger forces `is_verified` (true only if the user has a delivered order containing the item), sets `author` from the profile, and zeroes the counters.
- Add `unique (item_id, user_id)`.

### 0.5 Storage bucket is writable by every user 🔴 — S
**Where:** `005_storage_images.sql`.

**Problem:** any authenticated user can upload or **overwrite** any object in `images`, including product photos.

**Fix:**
- Only admins may insert or update under `products/` and `categories/`.
- Customers may insert only under `reviews/{auth.uid()}/…`.
- Drop the customer update policy, and drop `upsert = true` in `ImageStorageRepository`: paths are random UUIDs, so upsert isn't needed.

### 0.6 Hardcoded admin credentials 🔴 — S
**Problem:**
- `002` and `003` contain `admin@kindleaf.com / Admin@Kindleaf1` in plain text.
- `SessionManager` grants admin UI to that email on the client (`BOOTSTRAP_ADMIN_EMAIL`).

**Fix:**
- **Rotate the password now.**
- Remove the seed from the migrations and create admins from the Supabase dashboard.
- Delete the email bootstrap hack.

### 0.7 Smaller hardening — S
- **Notifications:** move all notification creation into DB triggers (order status change, points change, order placed). Then revoke customer `INSERT` on `notifications`. Today customers can insert arbitrary notifications for themselves, and the app creates them from the client.
- **Order status:** enforce valid status transitions server-side, with an `admin_update_order_status` RPC or trigger. Today the admin client decides the next status.
- **Backups:** set `android:allowBackup="false"` (or add backup rules that exclude the auth session store).
- **Version control:** put the project under git. It currently is not a repository. Confirm `.env` stays ignored.

---

## Phase 1 — Missing core e-commerce features

| # | Feature | Why | Effort |
|---|---|---|---|
| 1.1 | **Real payments (Razorpay).** Edge Function creates the Razorpay order → Android SDK checkout → webhook verifies the signature and sets `payment_status='paid'`. Refunds go through the Razorpay Refund API from `admin_complete_refund`. | No money can be collected today except by COD. | L |
| 1.2 | **Inventory.** Add `stock_qty` and `low_stock_threshold` to `items`. Decrement in `place_order` and restock on cancel. Show "Out of stock" in the UI and cap the quantity stepper. Add "Notify me when back". | No stock concept anywhere, so you can oversell. | M |
| 1.3 | **Persistent cart and wishlist.** Add `cart_items` and `wishlist_items` tables with RLS, plus a local DataStore/Room cache. | Both live only in memory and are lost when the app is killed. | M |
| 1.4 | **Working search, filter and sort.** Server-side search with Postgres full-text + `pg_trgm` (a `search_items(q, category, min_price, max_price, sort)` RPC). Store recent searches locally. | Typing in search does nothing; Filter/Sort buttons are empty. | M |
| 1.5 | **Customer reviews.** "Write a review" after delivery, using the existing unused `MultiImagePickerField`. `rating` and `review_count` on `items` kept up to date by a trigger. Remove the manual rating fields from the admin product form. Helpful votes in a `review_votes` table. | Customers can't write reviews; ratings are typed in by the admin. | M |
| 1.6 | **Leaf Points system.** `points_ledger` table (delta, reason, order_id, created_by). Earn on delivery; redeem at checkout inside `place_order`; admin adjusts by a delta with a reason instead of overwriting the balance; history screen in the profile. | The FAQ promises redemption at checkout, but it doesn't exist. Admin edits have no audit trail. | M |
| 1.7 | **Coupon rules.** Columns: `min_order`, `max_discount`, `fixed_amount`, `starts_at`, `usage_limit`, `per_user_limit`. Plus a `coupon_redemptions` table. Validate only on the server. | "15% off above ₹499" isn't enforced; there are no usage limits. | S–M |
| 1.8 | **Returns and exchanges.** A `return_requests` flow within 7 days of delivery. Or remove the claim from the FAQ. | The FAQ advertises a 7-day return policy. | M |
| 1.9 | **Order details and invoices.** Itemize multi-item orders, show the real data on the confirmation screen, add courier and tracking number, generate a GST invoice PDF (Edge Function), and add reorder. | The customer only sees the first item; the invoice button is dead. | M |
| 1.10 | **Account essentials.** Forgot/reset password, confirm-password on signup, proper email change through `auth.updateUser`, **in-app account deletion**, privacy policy and terms links. | Account deletion and a privacy policy are **Google Play requirements**. | S–M |
| 1.11 | **Push notifications and deep links.** FCM tokens table plus an Edge Function that sends on the notification insert trigger. Deep links such as `kindleaf://order/{id}`. | Notifications are in-app only; tapping one doesn't open the order. | M |
| 1.12 | **Support that works.** A `support_tickets` table (with an admin view), plus tappable call, email and WhatsApp buttons. | The "Send Message" button sends nothing. | S |
| 1.13 | **Pincode serviceability and address validation.** 6-digit pincode, 10-digit phone, state picker, auto-fill city from the pincode. | Addresses only check that fields aren't blank. | S |

---

## Phase 2 — Remove fake and placeholder content

These make the app look broken or misleading to real users. **Effort: S–M overall.**

**Fake data shown to real users (from `SampleData.kt`):**
- `ReviewsScreen:49` shows **sample reviews** when a product has none. Customers can't tell they're fake, so remove this first.
- `CheckoutScreen:61` and `CouponsScreen:28` fall back to sample coupons.
- `SessionManager` shows sample notifications to new users.
- `CatalogStore` falls back to sample products.
- `ProductDetailScreen:44` and `ReviewsScreen:43` show the *wrong product* when an id isn't found. Show a "not found" state instead.

**Hardcoded values:**
- Cart badge counts "3" (`ProductListingScreen:52`) and "2" (`ReviewsScreen:79`). Bind them to `cartItems.size`.
- The "Summer Wellness" banners and the expired "Buy 2 Get 1 / 10 Jun 2025" offer. Make these an admin-managed `banners` table.
- Version "1.0.0" in `AboutScreen`. Use `BuildConfig.VERSION_NAME`.
- "3–5 Business Days" on the confirmation screen, while the order stores +5 days.

**Dead buttons:** about 20 `onClick = {}` handlers. These include:
- Home bell, Search filter, Listing filter/sort, Share, tag chips.
- Coupon copy.
- Profile settings, Payment Methods, Leaf Points "View".
- Need Help, View Invoice, Rate App, Share App, Privacy/Terms/Licenses.

Each should either be wired up or removed.

**Other fixes:**
- Remove the Customer/Admin toggle from the login screen and route by `role` automatically.
- Checkout: auto-select the address the user just added. Today Place Order stays disabled with no hint after adding a first address.

---

## Phase 3 — Better ways to do existing things (architecture)

| Current approach | Better approach | Effort |
|---|---|---|
| Global `object` singletons (`AppStateManager`, `CatalogStore`, …) holding Compose state; logic inside composables; no ViewModels | **ViewModel + `StateFlow` per screen**, repository interfaces, Hilt for dependency injection. Survives rotation, is testable, and separates UI from logic. | L (incremental) |
| No `rememberSaveable`, so forms and checkout choices reset on rotation | `SavedStateHandle` in ViewModels | S (with the above) |
| Errors swallowed with `runCatching{}.getOrDefault(emptyList())`, so failures look like empty lists | A `UiState { Loading, Success, Empty, Error(retry) }` pattern; pull-to-refresh (`PullToRefreshBox`) | M |
| Money and discount math spread across the client (`AppStateManager`, `CouponsRepository.computeDiscount`, `ChargesStore`) | One pricing source of truth on the server (`place_order`). The client calls a `quote_cart` RPC only for preview. | covered by 0.2 |
| Admin dashboard downloads **all** items, profiles, orders and order lines to count them | An `admin_stats(from, to)` RPC or SQL view: revenue (paid/delivered only), orders per day, top products, low stock. Add charts. | M |
| No pagination (orders, notifications, reviews, customers, products) | `range()`-based paging, or Paging 3 | M |
| Order status timeline inferred from the current status | An `order_status_events` table (status, at, by) that the timeline reads real timestamps from | S |
| `items.category` stores the category **name**, so renaming a category orphans products | `category_id uuid references categories` | S |
| `estimated_delivery` stored as text | `date` column | S |
| String routes; admin routes unguarded; order detail is local state with no `BackHandler` | Type-safe Navigation Compose routes (`@Serializable`), a role guard on the admin graph, a real `order_detail/{id}` route, deep links | M |
| Admin panel shipped inside the customer APK | Either a separate product flavor (`customer` / `admin`) or a web admin (Next.js on the same Supabase; the `NEXT_PUBLIC_*` env names already hint at this). Web is easier for catalog and order operations. | M–L |
| Migrations: `003` repeats `002`, seeds mixed with schema, run by hand in the SQL editor | Supabase CLI (`supabase migration new`, `db push`); timestamped migrations; a separate `seed.sql`; a clean baseline squash | S |
| `.env` keys named `NEXT_PUBLIC_*` in an Android project | Rename to `SUPABASE_URL` / `SUPABASE_ANON_KEY`, and fail the build if they're missing | S |
| Sequential fetches in `bootstrapAfterAuth` and the dashboard | `coroutineScope { async … }` in parallel | S |
| Prices as `Int` rupees; percentage discounts truncate | Store paise (`integer`/`bigint`) and format on display | S |

---

## Phase 4 — Quality, compliance and release

- **Tests (there are none today).**
  - pgTAP tests for RLS and RPCs, especially 0.1–0.5, so the holes can't come back.
  - JVM unit tests for ViewModels.
  - A Compose UI test for the checkout happy path.
- **CI:** GitHub Actions running `assembleDebug`, `lint`, unit tests, and `supabase db lint`.
- **Release build:**
  - `isMinifyEnabled = true` with R8 keep rules for kotlinx-serialization, Ktor and Supabase.
  - A signing config taken from env or secrets.
  - A `versionCode` bump strategy.
  - Check Play's current target-API requirement and raise `targetSdk` from 35 if needed.
- **Observability:** Crashlytics or Sentry for crashes, and basic analytics (product views, add to cart, checkout started and completed).
- **Localization:** move all strings to `strings.xml` (currently only `app_name`), and add Hindi.
- **Dark mode:** replace hardcoded `DarkGreen`/`PrimaryGreen`/`SurfaceWhite` with `MaterialTheme.colorScheme`. Text on the Profile points banner and the About title is nearly invisible in dark mode.
- **Accessibility:** star-rating semantics, content descriptions on password toggles, touch targets of at least 48dp (the cart remove button is 24dp, the chip remove is 16dp).
- **Confirmations:** add confirm dialogs to destructive admin actions (delete product, category, coupon or review; cancel order; mark refunded) and to logout.

---

## Phase 5 — Growth features (after launch)

- **Subscribe & Save.** Tea is a repeat purchase, so a recurring monthly delivery at a discount is the highest-value addition for this catalog.
- **Product variants** (25 vs 50 cups, loose leaf vs bags) with MRP vs sale price, plus multiple images per product.
- **Guest browsing,** with login required only at checkout. This lowers the signup barrier.
- **Recently viewed, "frequently bought together" and wellness-goal recommendations** (Sleep, Immunity, Detox tags already exist).
- **Referral program** paid out in Leaf Points (once the ledger in 1.6 exists).
- **Bundles and gift boxes,** plus gift messages at checkout.
- **Shipping aggregator integration** (e.g. Shiprocket) for automatic AWB numbers and tracking webhooks that update order status.

---

## Suggested order of work

1. **Week 1:** all of Phase 0. Hide UPI/Card until 1.1 ships. Also the Phase 2 removal of fake reviews and coupons.
2. **Weeks 2–3:** 1.2 inventory, 1.3 persistent cart, 1.4 search, 1.5 reviews, 1.10 account essentials, and the rest of Phase 2.
3. **Weeks 3–5:** 1.1 Razorpay, 1.6 Leaf Points ledger, 1.7 coupon rules, 1.9 invoices, 1.11 push notifications.
4. **In parallel and ongoing:** move screens to ViewModels (Phase 3) as each one is touched, and add tests and CI (Phase 4).
5. **Before the Play Store:** release hardening, privacy policy, account deletion, localization pass.
