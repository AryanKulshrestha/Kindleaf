// Deletes the signed-in customer's account (Google Play requirement) through the Supabase Auth
// Admin API, after the database confirms nothing is still in progress (orders, refunds, returns).
// Order history is kept anonymised (orders.user_id is set to null by the foreign key).
import { adminClient, corsHeaders, errorResponse, HttpError, json, requireUser } from "../_shared/razorpay.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const user = await requireUser(req);
    const db = adminClient();

    const { data: blocker, error } = await db.rpc("account_deletion_blocker", { p_user: user.id });
    if (error) throw new HttpError(500, error.message);
    if (blocker) throw new HttpError(409, blocker as string);

    const { error: deleteError } = await db.auth.admin.deleteUser(user.id);
    if (deleteError) throw new HttpError(500, "Couldn't delete your account. Please contact support.");

    return json({ deleted: true });
  } catch (err) {
    return errorResponse(err);
  }
});
