// Creates (or reuses) the Razorpay order for a Kindleaf order that is awaiting payment
// and returns everything the Android Checkout SDK needs.
import {
  adminClient,
  corsHeaders,
  createRazorpayOrder,
  errorResponse,
  HttpError,
  json,
  type OrderRow,
  razorpayKeys,
  requireUser,
} from "../_shared/razorpay.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const user = await requireUser(req);
    const { order_id: orderId } = await req.json().catch(() => ({}));
    if (!orderId) throw new HttpError(400, "order_id is required");

    const { keyId } = razorpayKeys();
    const db = adminClient();

    const { data: found, error } = await db
      .from("orders")
      .select("id, user_id, status, payment_status, refund_status, refund_amount, total, razorpay_order_id, razorpay_payment_id, expires_at")
      .eq("id", orderId)
      .maybeSingle();
    if (error) throw new HttpError(500, error.message);
    let order = found as OrderRow | null;
    if (!order || order.user_id !== user.id) throw new HttpError(404, "Order not found");

    if (order.payment_status === "paid") throw new HttpError(409, "This order is already paid");
    if (order.status !== "pending_payment") {
      throw new HttpError(409, "This order is no longer awaiting payment");
    }
    if (order.expires_at && new Date(order.expires_at).getTime() < Date.now()) {
      // Cancel it now (releases the reserved stock) rather than waiting for the scheduled job.
      await db.rpc("expire_unpaid_orders");
      throw new HttpError(410, "The payment window for this order has expired. Please place the order again.");
    }
    if (order.total <= 0) throw new HttpError(400, "Nothing to pay for this order");

    if (order.payment_status === "failed") {
      const { data, error: resetError } = await db.rpc("payment_reset_for_retry", { p_order_id: order.id });
      if (resetError) throw new HttpError(500, resetError.message);
      order = data as OrderRow;
    }

    if (!order.razorpay_order_id) {
      const rpOrder = await createRazorpayOrder(order.total * 100, order.id, {
        kindleaf_order_id: order.id,
        user_id: user.id,
      });
      const { data, error: attachError } = await db.rpc("payment_attach_razorpay_order", {
        p_order_id: order.id,
        p_razorpay_order_id: rpOrder.id,
      });
      if (attachError) throw new HttpError(500, attachError.message);
      order = data as OrderRow;
    }

    const { data: profile } = await db
      .from("profiles")
      .select("name, email, phone")
      .eq("id", user.id)
      .maybeSingle();

    return json({
      key_id: keyId,
      order_id: order.id,
      razorpay_order_id: order.razorpay_order_id,
      amount: order.total * 100,
      currency: "INR",
      name: "Kindleaf",
      description: `Order #${order.id.slice(0, 8).toUpperCase()}`,
      expires_at: order.expires_at,
      prefill: {
        name: profile?.name ?? "",
        email: profile?.email || user.email || "",
        contact: profile?.phone ?? "",
      },
    });
  } catch (err) {
    return errorResponse(err);
  }
});
