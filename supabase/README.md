# Supabase — project "ti-masters"

- Project ref: `snbmafhkirlxyrnyzxrt` (org "prangedev's Org", free plan, region `sa-east-1`)
- API URL and publishable key: `app/build.gradle.kts` (`SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`). The publishable key is public by design; RLS protects the data. The service-role key must never be put in the app.
- Migrations: `migrations/` (applied in filename order).

## Dashboard settings that SQL cannot change

Authentication → URL Configuration:
- **Site URL:** `timasters://auth/confirm`
- **Redirect URLs:** add `timasters://auth/**`

Without these, the confirmation and password-reset e-mails open `http://localhost:3000` instead of the app.

## Known free-tier limits

- The built-in e-mail sender only delivers to members of the Supabase organization and a few messages per hour. Before launch, configure a custom SMTP (Authentication → Emails → SMTP Settings) and translate the e-mail templates to pt-BR.
- Free projects pause after 7 days without activity; restore them in the dashboard.
