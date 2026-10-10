import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) {
    return new Response("Unauthorized", { status: 401 });
  }

  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceRoleKey) {
    return new Response("Server configuration error", { status: 500 });
  }

  // Resolve identity from the caller's verified JWT. No uid is accepted in the
  // request body, so one authenticated user cannot nominate another for deletion.
  const caller = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
  });
  const { data, error: userError } = await caller.auth.getUser();
  if (userError || !data.user) {
    return new Response("Unauthorized", { status: 401 });
  }

  const admin = createClient(url, serviceRoleKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { error } = await admin.auth.admin.deleteUser(data.user.id);
  if (error) {
    console.error("delete-account failed", error.code);
    return new Response("Deletion failed", { status: 500 });
  }

  return new Response(null, { status: 204 });
});
