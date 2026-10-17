# Kindleaf backend setup

Everything the app needs server-side lives in this folder:

| Path | What it is |
|---|---|
| `migrations/001…008_*.sql` | Database schema, security rules (RLS) and business logic, applied in order |
| `functions/razorpay-*` | Edge Functions for Razorpay payments: create order, verify, webhook, refund |
| `functions/delete-account` | In-app account deletion through the Supabase Auth Admin API |
| `tests/` | Runs every migration on a real Postgres engine (PGlite) and checks the security + order rules |

---

## 1. Apply the database migration

Migration `008_security_commerce_payments.sql` locks down security and adds server-side pricing, stock,
Leaf Points ledger, coupon rules, verified reviews, returns, support tickets and payment state.

**Option A: SQL Editor.** Open Dashboard → SQL Editor, paste the whole file, and run it. It is idempotent and safe to re-run.

**Option B: Supabase CLI.**
```bash
supabase link --project-ref <your-project-ref>
supabase db push
```

After applying:
- **Change the admin password now.** Older versions of migrations 002/003 contained the admin password in plain text:
  Dashboard → Authentication → Users → admin@kindleaf.com → "Send password recovery" (or delete it and create a new admin).
- To make another user an admin, run: `update public.profiles set role = 'admin' where email = 'you@example.com';`
- **Set real stock.** Existing products were given 100 units each. Update them in the admin app → Products.
- **Ratings now come only from real reviews.** The previously hand-typed ratings were reset to each product's actual reviews (0 if none). Customers can review a product after it is delivered.
- (Optional) Enable the `pg_cron` extension (Database → Extensions) and re-run 008. Unpaid online orders are then auto-cancelled every 10 minutes. Without it, they are cancelled whenever someone places an order.

### Run the database tests (no Docker needed)
```bash
cd supabase/tests
npm install
npm test        # applies all migrations to an in-memory Postgres and runs 74 checks
```

---

## 2. Auth emails

**Password reset uses a 6-digit code** entered in the app. Dashboard → Authentication → Email Templates → **Reset Password**,
and make sure the template contains the code, for example:

```html
<h2>Reset your Kindleaf password</h2>
<p>Your code is: <strong>{{ .Token }}</strong></p>
<p>It expires in 1 hour. If you didn't ask for this, ignore this email.</p>
```

**Email change**: the default "Change Email Address" template works as is. The app's profile email updates automatically once the new address is confirmed.

---

## 3. Razorpay: the only step left for online payments

The app never contains your Razorpay secret. Checkout calls the Edge Functions, which hold the keys as Supabase secrets.

### 3.1 Get your keys
1. Create or log in to your account at https://dashboard.razorpay.com.
2. Switch to **Test Mode** (toggle in the top bar) while you try it out.
3. Account & Settings → **API Keys** → Generate Key. Copy the **Key ID** (`rzp_test_…`) and **Key Secret**.

### 3.2 Deploy the payment functions and set the secrets
```bash
supabase login
supabase link --project-ref <your-project-ref>

supabase secrets set \
  RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxx \
  RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxxxxxxxxxx \
  RAZORPAY_WEBHOOK_SECRET=choose-a-long-random-string

supabase functions deploy razorpay-create-order
supabase functions deploy razorpay-verify-payment
supabase functions deploy razorpay-refund
supabase functions deploy razorpay-webhook --no-verify-jwt   # Razorpay can't send a Supabase JWT; it's verified by HMAC signature instead
supabase functions deploy delete-account                      # in-app account deletion (Google Play requirement)
```
(Secrets can also be added in Dashboard → Edge Functions → Secrets.)

### 3.3 Add the webhook
Razorpay Dashboard → Account & Settings → **Webhooks** → Add New Webhook:
- **URL:** `https://<your-project-ref>.supabase.co/functions/v1/razorpay-webhook`
- **Secret:** the same value as `RAZORPAY_WEBHOOK_SECRET`
- **Events:** `payment.captured`, `payment.failed`, `order.paid`, `refund.processed`, `refund.failed`

The webhook makes payments reliable. If the customer's app closes right after paying, the order is still marked paid.

### 3.4 Test it
In the app: add to cart → Checkout → **Pay online** → Continue to payment.
In Test Mode use any test method from https://razorpay.com/docs/payments/payments/test-card-details/,
e.g. UPI ID `success@razorpay`, or card `4111 1111 1111 1111` with any future expiry and any CVV.

Then check:
- The order moves from **Awaiting payment** to **Order placed**. You get a "Payment received" notification.
- Admin app → Orders shows it as Paid.
- Cancel it as the customer and choose "Refund to original payment method". In the admin order screen press **Refund via Razorpay**. The refund appears in the Razorpay dashboard, and the webhook marks it completed.

### 3.5 Go live
1. Complete Razorpay KYC / account activation.
2. Generate **Live** keys and re-run `supabase secrets set` with `rzp_live_…` keys.
3. Create the webhook again in **Live Mode** (webhooks are per mode).
4. Keep "Payment Capture" on **Automatic** (Account & Settings → Payment Capture). The verify function captures authorized payments anyway as a fallback.

Payments can be switched off at any time without redeploying: Admin app → More → Store settings → "Online payments".

---

## 4. Store settings (in the admin app)

Admin → More → **Store settings**:
- Support email / phone / WhatsApp, shown on the Help screen. Hidden while empty.
- Privacy policy and Terms URLs, shown on signup and About. **Google Play requires a privacy policy URL.**
- Leaf Points earn % (default 5%) and max redeem % per order (default 20%).
- Return window (default 7 days), unpaid-order expiry (default 30 min), max quantity per item.
- COD on/off, online payments on/off.

---

## How payment works (reference)

```
App: Checkout ──place_order(online)──▶ DB: order = pending_payment (stock reserved, expires in 30 min)
App: Payment screen ──▶ fn razorpay-create-order ──▶ Razorpay Orders API (amount taken from the DB)
App: Razorpay Checkout (UPI / cards / netbanking / wallets)
App ──▶ fn razorpay-verify-payment: HMAC signature check + fetch payment ──▶ DB: payment_mark_paid → order = placed
Razorpay ──▶ fn razorpay-webhook (HMAC checked) ──▶ same payment_mark_paid (idempotent), failures, refunds
```
Money amounts always come from the database. The app never tells the server what to charge.
