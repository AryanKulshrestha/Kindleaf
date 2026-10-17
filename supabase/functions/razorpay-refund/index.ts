// Admin-only: sends the pending refund for a cancelled/returned online order back through Razorpay.
import {
  adminClient,
  corsHeaders,
  errorResponse,
  HttpError,
  issueRefund,
  json,
  type OrderRow,
  requireUser,
} from "../_shared/razorpay.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const user = await requireUser(req);
    const db = adminClient();

    const { data: profile } = await db.from("profiles").select("role").eq("id", user.id).maybeSingle();
    if (profile?.role !== "admin") throw new HttpError(403, "Admin only");

    const { order_id: orderId } = await req.json().catch(() => ({}));
    if (!orderId) throw new HttpError(400, "order_id is required");

    const { data: order, error } = await db
      .from("orders")
      .select("id, user_id, status, payment_status, refund_status, refund_amount, total, razorpay_order_id, razorpay_payment_id, expires_at")
      .eq("id", orderId)
      .maybeSingle();
    if (error) throw new HttpError(500, error.message);
    if (!order) throw new HttpError(404, "Order not found");

    const updated = await issueRefund(db, order as OrderRow);
    return json({ order: updated });
  } catch (err) {
    return errorResponse(err);
  }
});
