// Called only by the GitHub Actions release workflow (.github/workflows/release.yml).
// Auth: a GitHub Actions OIDC token (Authorization: Bearer ...) issued to a tag build of
// REPOSITORY. No shared secret exists anywhere. Deployed with verify_jwt = false because the
// token comes from GitHub, not from Supabase Auth.
//
// POST {"action":"prepare","version_code":10100,"version_name":"1.1.0"}
//   -> {"path": "...", "signed_url": "..."}  (PUT the APK bytes to signed_url)
// POST {"action":"commit","version_code":...,"version_name":...,"sha256":...,
//       "size_bytes":...,"notes":...,"min_supported_code":...}
//   -> {"ok": true, "removed": [old version codes]}
import { createClient } from "npm:@supabase/supabase-js@2";
import { createRemoteJWKSet, jwtVerify } from "npm:jose@5";

const REPOSITORY = "__OWNER__/ti-masters";
const AUDIENCE = "ti-masters-release";
const GITHUB_ISSUER = "https://token.actions.githubusercontent.com";
const BUCKET = "app-releases";
const KEEP_RELEASES = 5;

const githubKeys = createRemoteJWKSet(new URL(`${GITHUB_ISSUER}/.well-known/jwks`));

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** True only for an OIDC token of a workflow run started by a "v*" tag in REPOSITORY. */
async function isReleaseBuild(req: Request): Promise<boolean> {
  const token = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return false;
  try {
    const { payload } = await jwtVerify(token, githubKeys, { issuer: GITHUB_ISSUER, audience: AUDIENCE });
    return payload.repository === REPOSITORY && String(payload.ref ?? "").startsWith("refs/tags/v");
  } catch {
    return false;
  }
}

function apkPath(code: number, name: string): string {
  return `v${code}/TI-Masters-${name}.apk`;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  if (!(await isReleaseBuild(req))) return json({ error: "unauthorized" }, 401);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }
  const code = Number(body.version_code);
  const name = String(body.version_name ?? "");
  if (!Number.isInteger(code) || code <= 0 || !/^\d+\.\d+\.\d+$/.test(name)) {
    return json({ error: "invalid version" }, 400);
  }

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
  const path = apkPath(code, name);

  if (body.action === "prepare") {
    const { data, error } = await admin.storage.from(BUCKET).createSignedUploadUrl(path, { upsert: true });
    if (error) return json({ error: error.message }, 500);
    return json({ path, signed_url: data.signedUrl });
  }

  if (body.action === "commit") {
    const sha256 = String(body.sha256 ?? "");
    const size = Number(body.size_bytes);
    if (!/^[0-9a-f]{64}$/.test(sha256) || !Number.isInteger(size) || size <= 0) {
      return json({ error: "invalid file info" }, 400);
    }
    const { data: listed, error: listError } = await admin.storage.from(BUCKET).list(`v${code}`);
    if (listError) return json({ error: listError.message }, 500);
    if (!listed?.some((f) => `v${code}/${f.name}` === path)) return json({ error: "apk not uploaded" }, 400);

    const { error } = await admin.from("app_releases").upsert({
      version_code: code,
      version_name: name,
      notes: String(body.notes ?? "").slice(0, 4000),
      apk_path: path,
      sha256,
      size_bytes: size,
      min_supported_code: Math.max(0, Number(body.min_supported_code) || 0),
      published_at: new Date().toISOString(),
    });
    if (error) return json({ error: error.message }, 500);

    // Keep the newest releases only: the free plan has 1 GB of storage.
    const { data: old } = await admin
      .from("app_releases")
      .select("version_code, apk_path")
      .order("version_code", { ascending: false })
      .range(KEEP_RELEASES, 1000);
    const removed: number[] = [];
    if (old && old.length > 0) {
      await admin.storage.from(BUCKET).remove(old.map((r) => r.apk_path));
      await admin.from("app_releases").delete().in("version_code", old.map((r) => r.version_code));
      removed.push(...old.map((r) => r.version_code));
    }
    return json({ ok: true, removed });
  }

  return json({ error: "unknown action" }, 400);
});
