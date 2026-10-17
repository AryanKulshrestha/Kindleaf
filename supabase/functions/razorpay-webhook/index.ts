// Razorpay → Kindleaf webhook. Deploy with --no-verify-jwt (Razorpay does not send a Supabase JWT);
// every request is authenticated with the X-Razorpay-Signature HMAC instead.
//
// Subscribe to: payment.captured, payment.failed, order.paid, refund.processed, refund.failed
import {
  adminClient,
  errorResponse,
  HttpError,
  isValidWebhookSignature,
  json,
  markPaid,
  type RazorpayPayment,
  type RazorpayRefund,
} from "../_shared/razorpay.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    const rawBody = await req.text();
    const signature = req.headers.get("X-Razorpay-Signature") ?? "";
    if (!signature || !(await isValidWebhookSignature(rawBody, signature))) {
      throw new HttpError(401, "Invalid webhook signature");
    }

    const event = JSON.parse(rawBody);
    const db = adminClient();
    const payment: RazorpayPayment | undefined = event.payload?.payment?.entity;
    const refund: RazorpayRefund | undefined = event.payload?.refund?.entity;

    switch (event.event) {
      case "payment.captured":
      case "order.paid": {
        if (!payment?.order_id) break;
        if (!(await ownsRazorpayOrder(db, payment.order_id))) break;
        await markPaid(db, payment.order_id, payment);
        break;
      }
      case "payment.failed": {
        if (!payment?.order_id) break;
        const { error } = await db.rpc("payment_mark_failed", {
          p_razorpay_order_id: payment.order_id,
          p_reason: (payment as unknown as { error_description?: string }).error_description ?? null,
        });
        if (error) throw new HttpError(500, error.message);
        break;
      }
      case "refund.processed":
      case "refund.failed": {
        if (!refund?.payment_id) break;
        const { data: order } = await db
          .from("orders")
          .select("id")
          .eq("razorpay_payment_id", refund.payment_id)
          .maybeSingle();
        if (!order) break;
        const { error } = await db.rpc("payment_set_refund", {
          p_order_id: order.id,
          p_refund_id: refund.id,
          p_status: event.event === "refund.processed" ? "completed" : "failed",
        });
        if (error) throw new HttpError(500, error.message);
        break;
      }
      default:
        // Other events are acknowledged and ignored.
        break;
    }

    return json({ received: true });
  } catch (err) {
    // Non-2xx makes Razorpay retry, which is what we want for transient DB errors.
    return errorResponse(err);
  }
});

async function ownsRazorpayOrder(db: ReturnType<typeof adminClient>, razorpayOrderId: string) {
  const { data } = await db
    .from("orders")
    .select("id")
    .eq("razorpay_order_id", razorpayOrderId)
    .maybeSingle();
  if (!data) console.warn(`Ignoring webhook for unknown Razorpay order ${razorpayOrderId}`);
  return Boolean(data);
}
