// Runs every Kindleaf migration on PGlite (real Postgres compiled to WASM) with minimal Supabase stubs,
// then exercises the security rules and commerce flows as different users.
//
//   cd supabase/tests && npm install && npm test
//
// No Docker or Supabase project needed. Run it after every migration change.
import { PGlite } from "@electric-sql/pglite";
import { pg_trgm } from "@electric-sql/pglite/contrib/pg_trgm";
import { pgcrypto } from "@electric-sql/pglite/contrib/pgcrypto";
import fs from "node:fs";
import path from "node:path";

const MIGRATIONS = process.argv[2] ?? new URL("../migrations/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const db = new PGlite({ extensions: { pg_trgm, pgcrypto } });

const STUB = `
create schema if not exists extensions;
create schema if not exists auth;
create schema if not exists storage;
do $$ begin create role anon nologin; exception when duplicate_object then null; end $$;
do $$ begin create role authenticated nologin; exception when duplicate_object then null; end $$;
do $$ begin create role service_role nologin bypassrls; exception when duplicate_object then null; end $$;
grant usage on schema public, auth, storage, extensions to anon, authenticated, service_role;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
create table auth.users (
  id uuid primary key default gen_random_uuid(),
  email text,
  raw_user_meta_data jsonb default '{}'::jsonb,
  raw_app_meta_data jsonb default '{}'::jsonb,
  created_at timestamptz default now()
);
grant select on auth.users to authenticated, service_role;
create or replace function auth.uid() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant execute on function auth.uid() to anon, authenticated, service_role;
create table storage.buckets (id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]);
create table storage.objects (id uuid primary key default gen_random_uuid(), bucket_id text, name text, owner uuid);
alter table storage.objects enable row level security;
grant all on storage.objects to authenticated, service_role;
create or replace function storage.foldername(name text) returns text[] language sql immutable as $$
  select (string_to_array(name, '/'))[1:array_length(string_to_array(name, '/'), 1) - 1]
$$;
grant execute on function storage.foldername(text) to authenticated;
`;

let passed = 0;
let failed = 0;
const failures = [];

function ok(cond, name, detail = "") {
  if (cond) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    failures.push(`${name} ${detail}`);
    console.log(`  ✗ ${name} ${detail}`);
  }
}

/** Runs fn inside a transaction as the given role/user; rolls back only on error. */
async function as(role, userId, fn) {
  return db.transaction(async (tx) => {
    await tx.query(`select set_config('request.jwt.claim.sub', $1, true)`, [userId ?? ""]);
    if (role !== "postgres") await tx.exec(`set local role ${role}`);
    return fn(tx);
  });
}

async function expectError(name, promise, match) {
  try {
    await promise;
    ok(false, name, "(expected an error, got success)");
  } catch (e) {
    const msg = String(e.message ?? e);
    ok(!match || msg.toLowerCase().includes(match.toLowerCase()), name, `(error: ${msg})`);
  }
}

const one = async (tx, sql, params = []) => (await tx.query(sql, params)).rows[0];

async function main() {
  await db.exec(STUB);
  // SKIP_MIGRATIONS=004,005,006,007 simulates a database where those were never applied.
  const skip = (process.env.SKIP_MIGRATIONS ?? "").split(",").filter(Boolean);
  const files = fs.readdirSync(MIGRATIONS)
    .filter((f) => f.endsWith(".sql") && !skip.some((prefix) => f.startsWith(prefix)))
    .sort();
  for (const f of files) {
    const sql = fs.readFileSync(path.join(MIGRATIONS, f), "utf8");
    try {
      await db.exec(sql);
      console.log(`applied ${f}`);
    } catch (e) {
      console.log(`FAILED ${f}: ${e.message}`);
      throw e;
    }
  }
  // Re-running 008 must be safe (idempotent).
  await db.exec(fs.readFileSync(path.join(MIGRATIONS, "008_security_commerce_payments.sql"), "utf8"));
  console.log("re-applied 008 (idempotent)");

  // ── Users ──
  const A = "11111111-1111-4111-8111-111111111111";
  const B = "22222222-2222-4222-8222-222222222222";
  const ADMIN = "33333333-3333-4333-8333-333333333333";
  await db.query(
    `insert into auth.users (id, email, raw_user_meta_data) values
     ($1, 'a@test.in', '{"name":"Asha Rao"}'), ($2, 'b@test.in', '{"name":"Bala"}'), ($3, 'admin@test.in', '{"name":"Admin"}')`,
    [A, B, ADMIN],
  );
  await db.query(`update public.profiles set role = 'admin' where id = $1`, [ADMIN]);

  const items = (await db.query(`select id, name, price, stock_qty from public.items order by name`)).rows;
  const tulsi = items.find((i) => i.name === "Tulsi Green Tea");
  const chamomile = items.find((i) => i.name === "Chamomile Calm Tea");
  const herbal = items.find((i) => i.name === "Herbal Green Tea");

  console.log("\n# Profiles / privilege escalation");
  await expectError("customer cannot make self admin",
    as("authenticated", A, (tx) => tx.query(`update public.profiles set role='admin' where id=$1`, [A])), "role");
  await expectError("customer cannot set own leaf points",
    as("authenticated", A, (tx) => tx.query(`update public.profiles set leaf_points=99999 where id=$1`, [A])), "ledger");
  await as("authenticated", A, (tx) => tx.query(`update public.profiles set name='Asha R', email='hacker@x.com' where id=$1`, [A]));
  const pa = await one(db, `select name, email, role from public.profiles where id=$1`, [A]);
  ok(pa.name === "Asha R" && pa.email === "a@test.in" && pa.role === "customer", "name editable, email + role protected");
  await expectError("admin cannot set leaf points directly either",
    as("authenticated", ADMIN, (tx) => tx.query(`update public.profiles set leaf_points=5 where id=$1`, [B])), "ledger");

  console.log("\n# Direct order writes are blocked");
  await expectError("customer cannot insert orders directly",
    as("authenticated", A, (tx) => tx.query(`insert into public.orders (user_id, total) values ($1, 1)`, [A])), "permission denied");
  await expectError("customer cannot update orders directly",
    as("authenticated", A, (tx) => tx.query(`update public.orders set total = 1`)), "permission denied");
  await expectError("customer cannot call payment_mark_paid",
    as("authenticated", A, (tx) => tx.query(`select public.payment_mark_paid('x','y',null,null)`)), "permission denied");
  await expectError("customer cannot call internal _apply_points",
    as("authenticated", A, (tx) => tx.query(`select public._apply_points($1, 1000, 'refund')`, [A])), "permission denied");

  console.log("\n# Addresses");
  // A row saved by the old app (free-text phone) must not block later default-address changes.
  await db.exec(`alter table public.addresses disable trigger trg_addresses_validate`);
  await db.query(
    `insert into public.addresses (user_id, name, address_line, city, state, pincode, phone, is_default)
     values ($1,'Old','1 Old St','Pune','MH','4110','call me', true)`, [A]);
  await db.exec(`alter table public.addresses enable trigger trg_addresses_validate`);
  const normalized = await as("authenticated", A, (tx) => one(tx,
    `insert into public.addresses (user_id, name, address_line, city, state, pincode, phone)
     values ($1,'N','2 St','Pune','MH',' 411002','+91 98765-00000') returning phone, pincode`, [A]));
  ok(normalized.phone === "9876500000" && normalized.pincode === "411002", "phone/pincode normalised on save", JSON.stringify(normalized));
  await as("authenticated", A, (tx) => tx.query(`delete from public.addresses where user_id=$1 and name='N'`, [A]));
  await expectError("invalid pincode rejected",
    as("authenticated", A, (tx) => tx.query(
      `insert into public.addresses (user_id, name, address_line, city, state, pincode, phone) values ($1,'A','1 St','Pune','MH','12345','9876543210')`, [A])),
    "pincode");
  const addrA = await as("authenticated", A, (tx) => one(tx,
    `insert into public.addresses (user_id, name, address_line, city, state, pincode, phone, is_default)
     values ($1,'Asha','12 MG Road','Pune','Maharashtra','411001','9876543210', true) returning id`, [A]));
  ok((await one(db, `select count(*)::int n from public.addresses where user_id=$1 and is_default`, [A])).n === 1,
    "new default address clears a legacy (invalid) default row without error");
  const addrB = await as("authenticated", B, (tx) => one(tx,
    `insert into public.addresses (user_id, name, address_line, city, state, pincode, phone, is_default)
     values ($1,'Bala','5 Park St','Chennai','Tamil Nadu','600001','9123456780', true) returning id`, [B]));

  console.log("\n# Server pricing (quote_cart)");
  const cart1 = JSON.stringify([{ item_id: tulsi.id, quantity: 1 }]);
  const q1 = (await as("authenticated", A, (tx) => one(tx, `select public.quote_cart($1::jsonb) as q`, [cart1]))).q;
  ok(q1.subtotal === tulsi.price && q1.delivery_fee === 40 && q1.total === tulsi.price + 40,
    "subtotal from DB price + ₹40 delivery under ₹299", JSON.stringify(q1));
  const q2 = (await as("authenticated", A, (tx) => one(tx, `select public.quote_cart($1::jsonb, 'knd15') as q`, [cart1]))).q;
  ok(q2.coupon_discount === 0 && /more to use/.test(q2.coupon_error ?? ""), "KND15 needs ₹499 minimum", q2.coupon_error);
  const cart3 = JSON.stringify([{ item_id: tulsi.id, quantity: 3 }]);
  const q3 = (await as("authenticated", A, (tx) => one(tx, `select public.quote_cart($1::jsonb, 'KND15') as q`, [cart3]))).q;
  ok(q3.subtotal === 597 && q3.coupon_discount === Math.floor(597 * 15 / 100) && q3.delivery_fee === 0,
    "KND15 gives 15% at ₹597, free delivery above ₹299", JSON.stringify({ s: q3.subtotal, d: q3.coupon_discount, f: q3.delivery_fee }));
  const q4 = (await as("authenticated", A, (tx) => one(tx, `select public.quote_cart($1::jsonb) as q`,
    [JSON.stringify([{ item_id: tulsi.id, quantity: 1000 }])]))).q;
  ok(q4.errors.length > 0, "over-quantity / over-stock reported", JSON.stringify(q4.errors));

  console.log("\n# COD order");
  const stockBefore = (await one(db, `select stock_qty from public.items where id=$1`, [tulsi.id])).stock_qty;
  await as("authenticated", A, (tx) => tx.query(`insert into public.cart_items (user_id, item_id, quantity) values ($1,$2,2)`, [A, tulsi.id]));
  const cod = await as("authenticated", A, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'cod')`, [addrA.id, JSON.stringify([{ item_id: tulsi.id, quantity: 2 }])]));
  ok(cod.status === "placed" && cod.payment_status === "cod_pending" && cod.total === 398, "COD order placed with server total", JSON.stringify({ s: cod.status, p: cod.payment_status, t: cod.total }));
  const stockAfter = (await one(db, `select stock_qty from public.items where id=$1`, [tulsi.id])).stock_qty;
  ok(stockAfter === stockBefore - 2, "stock decremented");
  ok((await one(db, `select count(*)::int n from public.cart_items where user_id=$1`, [A])).n === 0, "saved cart cleared");
  ok((await one(db, `select count(*)::int n from public.notifications where user_id=$1 and order_id=$2`, [A, cod.id])).n === 1, "order-placed notification created by trigger");
  ok((await one(db, `select count(*)::int n from public.order_status_events where order_id=$1`, [cod.id])).n === 1, "status event recorded");
  const lines = (await db.query(`select price, quantity from public.order_items where order_id=$1`, [cod.id])).rows;
  ok(lines.length === 1 && lines[0].price === tulsi.price && lines[0].quantity === 2, "order lines use DB prices");
  const visibleToB = await as("authenticated", B, (tx) => tx.query(`select id from public.orders where id=$1`, [cod.id]));
  ok(visibleToB.rows.length === 0, "other customers cannot see the order");

  console.log("\n# Online order: unpaid cancel gives no refund");
  const online1 = await as("authenticated", A, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'online')`, [addrA.id, JSON.stringify([{ item_id: chamomile.id, quantity: 1 }])]));
  ok(online1.status === "pending_payment" && online1.payment_status === "pending" && online1.expires_at, "online order awaits payment");
  const c1 = await as("authenticated", A, (tx) => one(tx, `select * from public.cancel_own_order($1, 'leaf_points')`, [online1.id]));
  const ptsA0 = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  ok(c1.status === "cancelled" && c1.cancel_refund_choice === "none" && ptsA0 === 0,
    "unpaid order cancel credits NO Leaf Points (old exploit closed)", JSON.stringify({ choice: c1.cancel_refund_choice, pts: ptsA0 }));

  console.log("\n# Online order: paid via Razorpay then cancelled for Leaf Points");
  const online2 = await as("authenticated", A, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'online')`, [addrA.id, JSON.stringify([{ item_id: herbal.id, quantity: 1 }])]));
  await as("service_role", null, (tx) => tx.query(`select public.payment_attach_razorpay_order($1, 'order_RP1')`, [online2.id]));
  await expectError("amount mismatch rejected",
    as("service_role", null, (tx) => tx.query(`select public.payment_mark_paid('order_RP1', 'pay_1', 'upi', 1)`)), "mismatch");
  const paid = await as("service_role", null, (tx) => one(tx,
    `select * from public.payment_mark_paid('order_RP1', 'pay_1', 'upi', $1)`, [online2.total * 100]));
  ok(paid.status === "placed" && paid.payment_status === "paid" && paid.payment_method === "Online · UPI", "payment marks order placed + paid");
  const paidAgain = await as("service_role", null, (tx) => one(tx,
    `select * from public.payment_mark_paid('order_RP1', 'pay_1', 'upi', $1)`, [online2.total * 100]));
  ok(paidAgain.payment_status === "paid", "mark paid is idempotent (webhook + app both call it)");
  await expectError("paid order needs a refund choice",
    as("authenticated", A, (tx) => tx.query(`select public.cancel_own_order($1, 'none')`, [online2.id])), "choose");
  await as("authenticated", A, (tx) => tx.query(`select public.cancel_own_order($1, 'leaf_points')`, [online2.id]));
  const ptsA1 = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  ok(ptsA1 === online2.total * 10, "paid order refunded as Leaf Points (total × 10)", `${ptsA1}`);
  ok((await one(db, `select count(*)::int n from public.points_ledger where user_id=$1 and reason='refund'`, [A])).n === 1, "refund recorded in points ledger");

  console.log("\n# Redeeming Leaf Points");
  const cartBig = JSON.stringify([{ item_id: chamomile.id, quantity: 2 }]);
  const qp = (await as("authenticated", A, (tx) => one(tx, `select public.quote_cart($1::jsonb, null, 100000) as q`, [cartBig]))).q;
  ok(qp.points_discount === Math.floor(qp.subtotal * 20 / 100) && qp.points_applied === qp.points_discount * 10,
    "points capped at 20% of order", JSON.stringify({ sub: qp.subtotal, disc: qp.points_discount, used: qp.points_applied }));
  const withPts = await as("authenticated", A, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'cod', null, 100000)`, [addrA.id, cartBig]));
  const ptsA2 = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  ok(withPts.points_redeemed === qp.points_applied && ptsA2 === ptsA1 - qp.points_applied, "points deducted on order");
  await as("authenticated", A, (tx) => tx.query(`select public.cancel_own_order($1, 'none')`, [withPts.id]));
  const ptsA3 = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  ok(ptsA3 === ptsA1, "redeemed points returned on cancel");

  console.log("\n# Admin status machine + delivery rewards");
  await expectError("customer cannot call admin RPC",
    as("authenticated", A, (tx) => tx.query(`select public.admin_update_order_status($1, 'confirmed')`, [cod.id])), "admin only");
  await expectError("unknown order id reports not found",
    as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_update_order_status('00000000-0000-4000-8000-000000000000', 'confirmed')`)), "not found");
  await expectError("cannot skip placed → shipped",
    as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_update_order_status($1, 'shipped')`, [cod.id])), "cannot move");
  for (const s of ["confirmed", "packed"]) {
    await as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_update_order_status($1, $2)`, [cod.id, s]));
  }
  await as("authenticated", ADMIN, (tx) => tx.query(
    `select public.admin_update_order_status($1, 'shipped', 'AWB123', 'Delhivery')`, [cod.id]));
  const shippedNote = await one(db, `select message from public.notifications where order_id=$1 order by created_at desc limit 1`, [cod.id]);
  ok(/AWB123/.test(shippedNote.message), "shipping notification includes tracking", shippedNote.message);
  await as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_update_order_status($1, 'delivered')`, [cod.id]));
  const delivered = await one(db, `select * from public.orders where id=$1`, [cod.id]);
  const ptsA4 = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  ok(delivered.payment_status === "paid" && delivered.delivered_at, "COD marked paid on delivery");
  ok(delivered.points_earned === Math.floor(delivered.total * 5 / 10) && ptsA4 === ptsA3 + delivered.points_earned,
    "5% back in Leaf Points on delivery", `${delivered.points_earned}`);
  await expectError("customer cannot cancel a delivered order",
    as("authenticated", A, (tx) => tx.query(`select public.cancel_own_order($1, 'none')`, [cod.id])), "no longer");

  console.log("\n# Reviews");
  await expectError("cannot review an item not delivered to you",
    as("authenticated", B, (tx) => tx.query(
      `insert into public.reviews (item_id, user_id, rating, text) values ($1, $2, 5, 'great')`, [tulsi.id, B])), "delivered");
  const rv = await as("authenticated", A, (tx) => one(tx,
    `insert into public.reviews (item_id, user_id, rating, text, author, is_verified, helpful_count)
     values ($1, $2, 4, 'Lovely tulsi', 'Fake Name', false, 999) returning *`, [tulsi.id, A]));
  ok(rv.author === "Asha R" && rv.is_verified === true && rv.helpful_count === 0, "author/verified/counters set by server");
  await expectError("one review per product",
    as("authenticated", A, (tx) => tx.query(`insert into public.reviews (item_id, user_id, rating, text) values ($1,$2,5,'again')`, [tulsi.id, A])), "duplicate");
  const it = await one(db, `select rating, review_count from public.items where id=$1`, [tulsi.id]);
  ok(Number(it.rating) === 4 && it.review_count === 1, "item rating recomputed from real reviews", JSON.stringify(it));
  await expectError("admin cannot hand-type ratings (ignored)",
    (async () => {
      await as("authenticated", ADMIN, (tx) => tx.query(`update public.items set rating = 5, review_count = 500 where id=$1`, [tulsi.id]));
      const after = await one(db, `select rating, review_count from public.items where id=$1`, [tulsi.id]);
      if (Number(after.rating) === 4 && after.review_count === 1) throw new Error("ignored as expected");
    })(), "ignored");
  await as("authenticated", B, (tx) => tx.query(`insert into public.review_votes (review_id, user_id, is_helpful) values ($1,$2,true)`, [rv.id, B]));
  ok((await one(db, `select helpful_count from public.reviews where id=$1`, [rv.id])).helpful_count === 1, "helpful vote counted");
  await expectError("cannot vote on own review",
    as("authenticated", A, (tx) => tx.query(`insert into public.review_votes (review_id, user_id, is_helpful) values ($1,$2,true)`, [rv.id, A])), "row-level security");

  console.log("\n# Returns");
  const ret = await as("authenticated", A, (tx) => one(tx,
    `select * from public.request_return($1, 'Damaged or leaking packaging', 'box torn', 'leaf_points')`, [cod.id]));
  ok(ret.status === "requested", "return requested within window");
  const stockPreReturn = (await one(db, `select stock_qty from public.items where id=$1`, [tulsi.id])).stock_qty;
  const ptsPreReturn = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  await as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_resolve_return($1, true, 'ok')`, [ret.id]));
  const returned = await one(db, `select status, refund_status, payment_status from public.orders where id=$1`, [cod.id]);
  const stockPostReturn = (await one(db, `select stock_qty from public.items where id=$1`, [tulsi.id])).stock_qty;
  const ptsPostReturn = (await one(db, `select leaf_points from public.profiles where id=$1`, [A])).leaf_points;
  ok(returned.status === "returned" && returned.refund_status === "completed", "return approved → order returned + refunded");
  ok(stockPostReturn === stockPreReturn + 2, "returned items restocked");
  ok(ptsPostReturn === ptsPreReturn - delivered.points_earned + delivered.total * 10, "earned points reversed, refund credited");

  console.log("\n# Notifications");
  await expectError("customer cannot create notifications",
    as("authenticated", A, (tx) => tx.query(`insert into public.notifications (user_id, title, message) values ($1,'x','y')`, [A])), "row-level security");
  await expectError("customer cannot rewrite notification text",
    as("authenticated", A, (tx) => tx.query(`update public.notifications set title='hacked' where user_id=$1`, [A])), "permission denied");
  await as("authenticated", A, (tx) => tx.query(`update public.notifications set is_read=true where user_id=$1`, [A]));
  ok((await one(db, `select bool_and(is_read) b from public.notifications where user_id=$1`, [A])).b === true, "customer can mark read");

  console.log("\n# Admin points adjustments");
  await expectError("customer cannot adjust points",
    as("authenticated", A, (tx) => tx.query(`select public.admin_adjust_points($1, 100, 'gift')`, [A])), "admin only");
  await expectError("adjustment needs a reason",
    as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_adjust_points($1, 100, '')`, [B])), "reason");
  const bal = await as("authenticated", ADMIN, (tx) => one(tx, `select public.admin_adjust_points($1, 150, 'Welcome gift') as b`, [B]));
  ok(bal.b === 150, "admin credit applied via ledger");
  await expectError("cannot deduct below zero",
    as("authenticated", ADMIN, (tx) => tx.query(`select public.admin_adjust_points($1, -1000, 'oops')`, [B])), "insufficient");

  console.log("\n# Payment fallbacks + expiry");
  const online3 = await as("authenticated", B, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'online')`, [addrB.id, JSON.stringify([{ item_id: herbal.id, quantity: 1 }])]));
  const cod3 = await as("authenticated", B, (tx) => one(tx, `select * from public.switch_order_to_cod($1)`, [online3.id]));
  ok(cod3.status === "placed" && cod3.payment_status === "cod_pending", "switch to COD after failed payment");
  const online4 = await as("authenticated", B, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'online')`, [addrB.id, JSON.stringify([{ item_id: herbal.id, quantity: 3 }])]));
  const herbalBefore = (await one(db, `select stock_qty from public.items where id=$1`, [herbal.id])).stock_qty;
  await db.query(`update public.orders set expires_at = now() - interval '1 minute' where id=$1`, [online4.id]);
  const expired = await as("service_role", null, (tx) => one(tx, `select public.expire_unpaid_orders() as n`));
  const o4 = await one(db, `select status from public.orders where id=$1`, [online4.id]);
  const herbalAfter = (await one(db, `select stock_qty from public.items where id=$1`, [herbal.id])).stock_qty;
  ok(expired.n === 1 && o4.status === "cancelled" && herbalAfter === herbalBefore + 3, "expired unpaid order cancelled + restocked");
  await as("service_role", null, (tx) => tx.query(`select public.payment_attach_razorpay_order($1, 'order_LATE')`, [online4.id]));
  // attach only works while pending; simulate a late payment on an order that was attached before expiry:
  const online5 = await as("authenticated", B, (tx) => one(tx,
    `select * from public.place_order($1, $2::jsonb, 'online')`, [addrB.id, JSON.stringify([{ item_id: herbal.id, quantity: 1 }])]));
  await as("service_role", null, (tx) => tx.query(`select public.payment_attach_razorpay_order($1, 'order_RP5')`, [online5.id]));
  await as("authenticated", B, (tx) => tx.query(`select public.cancel_own_order($1, 'none')`, [online5.id]));
  const late = await as("service_role", null, (tx) => one(tx,
    `select * from public.payment_mark_paid('order_RP5', 'pay_5', 'card', $1)`, [online5.total * 100]));
  ok(late.status === "cancelled" && late.payment_status === "paid" && late.refund_status === "pending",
    "payment after cancellation is queued for automatic refund");
  const refunded = await as("service_role", null, (tx) => one(tx, `select * from public.payment_set_refund($1, 'rfnd_1', 'completed')`, [online5.id]));
  ok(refunded.refund_status === "completed" && refunded.payment_status === "refunded", "refund completion recorded");

  console.log("\n# Storage");
  await expectError("customer cannot upload product images",
    as("authenticated", A, (tx) => tx.query(`insert into storage.objects (bucket_id, name) values ('images', 'products/x.jpg')`)), "row-level security");
  await as("authenticated", A, (tx) => tx.query(`insert into storage.objects (bucket_id, name) values ('images', $1)`, [`reviews/${A}/photo.jpg`]));
  ok(true, "customer can upload into own review folder");
  await expectError("customer cannot upload into another user's review folder",
    as("authenticated", A, (tx) => tx.query(`insert into storage.objects (bucket_id, name) values ('images', $1)`, [`reviews/${B}/photo.jpg`])), "row-level security");

  console.log("\n# Search, stats, support, account deletion");
  const s1 = await as("anon", null, (tx) => tx.query(`select name from public.search_items('tulsi')`));
  ok(s1.rows.some((r) => r.name === "Tulsi Green Tea"), "search by name (anon)");
  const s2 = await as("authenticated", A, (tx) => tx.query(`select name from public.search_items('', 'Relaxation')`));
  ok(s2.rows.length === 1 && s2.rows[0].name === "Chamomile Calm Tea", "filter by category");
  const s3 = await as("authenticated", A, (tx) => tx.query(`select name, price from public.search_items('', null, null, null, 'price_desc')`));
  ok(s3.rows[0].price >= s3.rows[s3.rows.length - 1].price, "sort by price desc");
  const stats = await as("authenticated", ADMIN, (tx) => one(tx, `select public.admin_stats(30) as s`));
  ok(stats.s.customers === 2 && Array.isArray(stats.s.daily) && typeof stats.s.revenue === "number", "admin_stats works", JSON.stringify({ c: stats.s.customers, r: stats.s.revenue }));
  await expectError("customer cannot read admin stats",
    as("authenticated", A, (tx) => tx.query(`select public.admin_stats(30)`)), "admin only");
  await as("authenticated", A, (tx) => tx.query(`insert into public.support_tickets (user_id, email, subject, message) values ($1,'a@test.in','Help','Where is my order?')`, [A]));
  await expectError("customer cannot pre-set ticket status",
    as("authenticated", A, (tx) => tx.query(`insert into public.support_tickets (user_id, email, subject, message, status) values ($1,'a@test.in','x','y','resolved')`, [A])), "row-level security");
  const blocker = await as("service_role", null, (tx) => one(tx, `select public.account_deletion_blocker($1) as m`, [B]));
  ok(/in progress/.test(blocker.m ?? ""), "account deletion blocked while an order is in progress", blocker.m);
  const adminBlocker = await as("service_role", null, (tx) => one(tx, `select public.account_deletion_blocker($1) as m`, [ADMIN]));
  ok(/Admin accounts/.test(adminBlocker.m ?? ""), "admin accounts cannot self-delete");
  await expectError("customers cannot call the deletion check directly",
    as("authenticated", A, (tx) => tx.query(`select public.account_deletion_blocker($1)`, [A])), "permission denied");

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed) {
    console.log("FAILURES:\n - " + failures.join("\n - "));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error("FATAL", e);
  process.exit(2);
});
