// Called by the app right after Razorpay Checkout succeeds. Verifies the signature
// with the secret key, confirms the payment with Razorpay, and marks the order paid.
// The webhook does the same thing server-to-server, so a closed app never loses a payment.
import {
  adminClient,
  capturePayment,
  corsHeaders,
  errorResponse,
  fetchPayment,
  HttpError,
  isValidPaymentSignature,
  json,
  markPaid,
  requireUser,
} from "../_shared/razorpay.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const user = await requireUser(req);
    const body = await req.json().catch(() => ({}));
    const orderId: string | undefined = body.order_id;
    const razorpayOrderId: string | undefined = body.razorpay_order_id;
    const razorpayPaymentId: string | undefined = body.razorpay_payment_id;
    const signature: string | undefined = body.razorpay_signature;
    if (!orderId || !razorpayOrderId || !razorpayPaymentId || !signature) {
      throw new HttpError(400, "Missing payment details");
    }

    const db = adminClient();
    const { data: order, error } = await db
      .from("orders")
      .select("id, user_id, total, razorpay_order_id")
      .eq("id", orderId)
      .maybeSingle();
    if (error) throw new HttpError(500, error.message);
    if (!order || order.user_id !== user.id) throw new HttpError(404, "Order not found");
    if (order.razorpay_order_id !== razorpayOrderId) throw new HttpError(400, "Payment does not match this order");

    if (!(await isValidPaymentSignature(razorpayOrderId, razorpayPaymentId, signature))) {
      throw new HttpError(400, "Payment signature is invalid");
    }

    let payment = await fetchPayment(razorpayPaymentId);
    if (payment.order_id !== razorpayOrderId) throw new HttpError(400, "Payment does not match this order");
    if (payment.status === "authorized") {
      // Only needed when auto-capture is off in the Razorpay dashboard.
      payment = await capturePayment(payment.id, order.total * 100);
    }
    if (payment.status !== "captured") {
      throw new HttpError(402, "Payment has not been completed yet");
    }

    const updated = await markPaid(db, razorpayOrderId, payment);
    return json({ order: updated });
  } catch (err) {
    return errorResponse(err);
  }
});
