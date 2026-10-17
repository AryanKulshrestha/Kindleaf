// Shared helpers for Kindleaf's Razorpay Edge Functions.
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export function errorResponse(err: unknown): Response {
  if (err instanceof HttpError) {
    return json({ error: err.message }, err.status);
  }
  console.error(err);
  const message = err instanceof Error ? err.message : "Unexpected error";
  return json({ error: message }, 500);
}

function requireEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) {
    throw new HttpError(
      503,
      `Online payments are not configured yet (missing ${name}). Please use Cash on Delivery.`,
    );
  }
  return value;
}

export function razorpayKeys() {
  return {
    keyId: requireEnv("RAZORPAY_KEY_ID"),
    keySecret: requireEnv("RAZORPAY_KEY_SECRET"),
  };
}

export function webhookSecret(): string {
  return requireEnv("RAZORPAY_WEBHOOK_SECRET");
}

/** Service-role client: bypasses RLS. Only use after authorising the caller. */
export function adminClient(): SupabaseClient {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false, autoRefreshToken: false } },
  );
}

/** Resolves the signed-in user from the request's Authorization header. */
export async function requireUser(req: Request) {
  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    throw new HttpError(401, "Please sign in again");
  }
  const client = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    {
      global: { headers: { Authorization: authHeader } },
      auth: { persistSession: false, autoRefreshToken: false },
    },
  );
  const { data, error } = await client.auth.getUser(authHeader.slice("Bearer ".length));
  if (error || !data.user) {
    throw new HttpError(401, "Please sign in again");
  }
  return data.user;
}

// ── Razorpay REST API ──

const API = "https://api.razorpay.com/v1";

async function razorpayRequest<T>(
  method: "GET" | "POST",
  path: string,
  body?: Record<string, unknown>,
): Promise<T> {
  const { keyId, keySecret } = razorpayKeys();
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      Authorization: `Basic ${btoa(`${keyId}:${keySecret}`)}`,
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok) {
    const description = payload?.error?.description ?? `Razorpay request failed (${res.status})`;
    throw new HttpError(502, description);
  }
  return payload as T;
}

export interface RazorpayOrder {
  id: string;
  amount: number;
  currency: string;
  status: string;
}

export interface RazorpayPayment {
  id: string;
  order_id: string;
  amount: number;
  currency: string;
  status: "created" | "authorized" | "captured" | "refunded" | "failed";
  method: string;
}

export interface RazorpayRefund {
  id: string;
  payment_id: string;
  amount: number;
  status: "pending" | "processed" | "failed";
}

export function createRazorpayOrder(amountPaise: number, receipt: string, notes: Record<string, string>) {
  return razorpayRequest<RazorpayOrder>("POST", "/orders", {
    amount: amountPaise,
    currency: "INR",
    receipt: receipt.slice(0, 40),
    notes,
  });
}

export function fetchPayment(paymentId: string) {
  return razorpayRequest<RazorpayPayment>("GET", `/payments/${encodeURIComponent(paymentId)}`);
}

export function capturePayment(paymentId: string, amountPaise: number) {
  return razorpayRequest<RazorpayPayment>("POST", `/payments/${encodeURIComponent(paymentId)}/capture`, {
    amount: amountPaise,
    currency: "INR",
  });
}

export function refundPayment(paymentId: string, amountPaise: number, orderId: string) {
  return razorpayRequest<RazorpayRefund>("POST", `/payments/${encodeURIComponent(paymentId)}/refund`, {
    amount: amountPaise,
    speed: "normal",
    receipt: `refund-${orderId}`.slice(0, 40),
    notes: { kindleaf_order_id: orderId },
  });
}

// ── Signatures ──

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function isValidPaymentSignature(
  razorpayOrderId: string,
  razorpayPaymentId: string,
  signature: string,
): Promise<boolean> {
  const { keySecret } = razorpayKeys();
  const expected = await hmacSha256Hex(keySecret, `${razorpayOrderId}|${razorpayPaymentId}`);
  return timingSafeEqual(expected, signature);
}

export async function isValidWebhookSignature(rawBody: string, signature: string): Promise<boolean> {
  const expected = await hmacSha256Hex(webhookSecret(), rawBody);
  return timingSafeEqual(expected, signature);
}

// ── Order helpers shared by verify / webhook / refund ──

export interface OrderRow {
  id: string;
  user_id: string | null;
  status: string;
  payment_status: string;
  refund_status: string;
  refund_amount: number;
  total: number;
  razorpay_order_id: string | null;
  razorpay_payment_id: string | null;
  expires_at: string | null;
}

/**
 * Sends a Razorpay refund for an order whose refund_status is pending/failed and records the result.
 * Returns the updated order row.
 */
export async function issueRefund(db: SupabaseClient, order: OrderRow): Promise<OrderRow> {
  if (!order.razorpay_payment_id) {
    throw new HttpError(
      400,
      "This order was not paid online. Refund it manually (e.g. bank transfer), then mark it refunded.",
    );
  }
  if (!["pending", "failed"].includes(order.refund_status)) {
    throw new HttpError(409, "There is no pending refund for this order");
  }
  const amount = (order.refund_amount > 0 ? order.refund_amount : order.total) * 100;
  let status: "processing" | "completed" | "failed";
  let refundId: string | null = null;
  try {
    const refund = await refundPayment(order.razorpay_payment_id, amount, order.id);
    refundId = refund.id;
    status = refund.status === "processed" ? "completed" : refund.status === "failed" ? "failed" : "processing";
  } catch (err) {
    await db.rpc("payment_set_refund", { p_order_id: order.id, p_refund_id: null, p_status: "failed" });
    throw err;
  }
  const { data, error } = await db.rpc("payment_set_refund", {
    p_order_id: order.id,
    p_refund_id: refundId,
    p_status: status,
  });
  if (error) throw new HttpError(500, error.message);
  return data as OrderRow;
}

/** Marks an order paid (idempotent) and auto-refunds if it had already been cancelled. */
export async function markPaid(
  db: SupabaseClient,
  razorpayOrderId: string,
  payment: Pick<RazorpayPayment, "id" | "method" | "amount">,
): Promise<OrderRow> {
  const { data, error } = await db.rpc("payment_mark_paid", {
    p_razorpay_order_id: razorpayOrderId,
    p_razorpay_payment_id: payment.id,
    p_method: payment.method ?? null,
    p_amount_paise: payment.amount,
  });
  if (error) throw new HttpError(500, error.message);
  const order = data as OrderRow;
  if (order.status === "cancelled" && order.refund_status === "pending" && order.razorpay_payment_id) {
    try {
      return await issueRefund(db, order);
    } catch (err) {
      console.error("Auto-refund failed; admin can retry from the order screen", err);
    }
  }
  return order;
}
