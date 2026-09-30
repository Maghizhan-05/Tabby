# Tabby — a minimal spending tracker

Tabby (product name; Xcode project `SpendTracker`) is a native iOS SwiftUI app for
logging expenses in a single tap. It is **offline-first**: every write lands in a
local SwiftData store immediately and syncs to Supabase in the background when
configured. It ships with a WidgetKit extension (home-screen spending rings) and a
Live Activity / Dynamic Island confirmation, plus an App Intent bindable to **Back Tap**.

## Highlights

- **Quick entry** bottom sheet: autofocused decimal amount with dominant typography,
  a searchable category field that offers **Add "<text>"** for unmatched input, an
  optional note (120 characters), an inline date picker (defaults to now), and one
  full-width Submit.
- **Home** split layout: top-half analytics with a compact mode selector over a single
  chart/ring canvas (Daily · Weekly · Monthly · Yearly · Categories · Trends, all
  Swift Charts); calm recent-entries list below.
- **Widget**: small + medium spending rings from the shared App-Group store; tap deep-links
  into quick entry.
- **Live Activity**: animated confirmation ring on submit (Dynamic Island expanded/compact/minimal);
  degrades gracefully where unavailable.
- **Auth**: email/password plus Sign in with Apple / Google. Unconfigured providers render
  **disabled** (not hidden) with helper text; missing Supabase keys show a non-blocking banner.

## Requirements

- Xcode 27 (built/tested against Xcode 27.0, iOS 27 SDK)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)
- Target simulator: **iPhone 18 Pro**

## Setup

```bash
# 1. Provide Supabase keys (optional for building; required for auth/sync)
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig
#    edit Config/Secrets.xcconfig  (Secrets.xcconfig is gitignored)

# 2. Generate the Xcode project
xcodegen generate

# 3. Open in Xcode, or build/install on the simulator
open SpendTracker.xcodeproj
# or:
./run.sh
```

`run.sh` re-signs the final simulator app and widget bundles with their App
Group entitlements before installation, which is required for a real shared
widget store on this Xcode 27 setup.

The app **builds and runs without Supabase keys** — it treats missing/empty keys as
"not configured" and disables the cloud paths. Add real keys to enable auth and sync.

### Supabase keys and the `//` caveat

Supabase renamed the old **anon key** to the **publishable key**. This app standardizes
on `SUPABASE_URL` / `SUPABASE_PUBLISHABLE_KEY` internally, but `Config/Secrets.xcconfig`
holds the values under Supabase's new public names:

```
NEXT_PUBLIC_SUPABASE_URL=xxxxxxxx.supabase.co
NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY=sb_publishable_xxxxxxxx
```

`Config/App.xcconfig` (committed, non-secret) `#include`s `Secrets.xcconfig` and bridges
those names to the build settings the app reads:

```
SUPABASE_URL = $(NEXT_PUBLIC_SUPABASE_URL)
SUPABASE_PUBLISHABLE_KEY = $(NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY)
```

**The `//` caveat.** xcconfig treats `//` as the start of a comment, and the slashes do
**not** reliably survive Info.plist substitution even when escaped with `$()`. So write the
URL **without the scheme** (host only, no `https://`). `SupabaseClientProvider.repairURLString`
prepends `https://` at runtime (and also repairs `https:/host` / `https:host` forms).
`SUPABASE_PUBLISHABLE_KEY` also falls back to a legacy `SUPABASE_ANON_KEY` Info.plist key
for backward compatibility.

These flow into the app via Info.plist keys and are read by `SupabaseClientProvider`.
**No keys are hardcoded in source**, and `Config/Secrets.xcconfig` is gitignored.

## Supabase setup

1. Create a project at [supabase.com](https://supabase.com).
2. In the SQL editor, run `supabase/schema.sql` (creates `profiles`, `categories`,
   `expenses`, enables **Row Level Security**, and adds per-user select/insert/update/delete
   policies scoped to `auth.uid()`).
3. Optionally run `supabase/seed.sql` while authenticated to seed default categories server-side.
4. Copy the project URL and the publishable key into `Config/Secrets.xcconfig` (see above).

For an existing project created before expense notes, run the idempotent migration
`supabase/migrations/20260930_add_expense_note.sql` in the SQL editor:

```sql
alter table public.expenses add column if not exists note text;
```

Local notes work without this migration. Cloud upserts containing a non-empty note require
the new column; note-less rows remain compatible with the pre-migration schema.

### Configuring Apple + Google OAuth providers

The app uses the Supabase OAuth **web flow** (`ASWebAuthenticationSession`) with a custom
URL scheme redirect. The app declares the `spendtracker` URL scheme in `Info.plist` and
uses **`spendtracker://auth-callback`** as the OAuth redirect. `SpendTrackerApp`'s
`.onOpenURL` forwards that callback to `client.auth.session(from:)` to complete the round-trip.

In the Supabase dashboard → **Authentication → URL Configuration**, add
`spendtracker://auth-callback` to the **Redirect URLs** allow-list.

Then in **Authentication → Providers**:

- **Apple**: enable Apple. Create a **Services ID** in the Apple Developer portal, configure
  Sign in with Apple, and add Supabase's callback
  `https://<project-ref>.supabase.co/auth/v1/callback` as a Return URL. Paste the Services ID,
  Team ID, Key ID, and the .p8 private key into Supabase.
- **Google**: enable Google. In Google Cloud Console create an OAuth 2.0 Client ID, add the same
  `https://<project-ref>.supabase.co/auth/v1/callback` as an Authorized redirect URI, and paste the
  Client ID + secret into Supabase.

The Apple/Google buttons are enabled when Supabase is configured and
`APPLE_SIGNIN_ENABLED` / `GOOGLE_SIGNIN_ENABLED` (Info.plist bools, via xcconfig) are `YES`.
Configured taps genuinely start the OAuth flow; if a provider isn't enabled server-side the
Supabase error is surfaced to the user.

## Binding Back Tap

`LogExpenseIntent` opens Tabby straight to quick entry and is exposed via `SpendTrackerShortcuts`.
On device: **Settings → Accessibility → Touch → Back Tap → Double Tap → pick the Tabby shortcut**
("Log Expense"). Back Tap only appears on real hardware.

## Architecture & rationale

- **iOS 17.0 minimum.** SwiftData, the modern `SectorMark` donut charts, and
  `containerBackground` widgets are all clean on 17+, which keeps the analytics and widget code simple.
- **SwiftData** for persistence: a single `ModelContainer` stored in the App Group container
  (`group.com.maghizhan.spendtracker`) so app, widget, and intent share one store. Default categories
  are seeded on first run.
- **Offline-first**: writes are local-first; `SyncEngine` pushes unsynced records and upserts by UUID
  (idempotent). Cloud access sits behind `AuthServicing` / `ExpenseRepositoring` / `CategoryRepositoring`
  protocols with Supabase-backed implementations, so the app compiles and runs regardless of SDK/config state.

## Known limitations

- **Live Activities cannot host an input form** — the Dynamic Island / Live Activity is
  **confirmation-only**; the actual entry form is the in-app bottom sheet.
- **Back Tap binding is only verifiable on real hardware** (not in the simulator).
- **Supabase live-project keys enable auth and sync.** Without them the app runs fully offline
  with cloud paths disabled; with them, email/password + Apple/Google OAuth and background sync
  are active.
- **Cloud sync is implemented.** `SyncEngine` pushes unsynced/dirty expenses and categories via
  the Supabase-backed repositories (`.from("expenses")` / `.from("categories")` upsert-by-UUID,
  delete-by-id), scoped to `auth.uid()` via RLS. Records are marked `.synced` only after a
  successful backend write; both `Expense` and `Category` carry a per-record sync flag so nothing
  is re-pushed every run.
- **Apple/Google OAuth is implemented** via `client.auth.signInWithOAuth` using the
  `spendtracker://auth-callback` redirect; configured taps start the real flow.
- Built and tested against **Xcode 27 / iOS 27 SDK targeting the iPhone 18 Pro simulator**.
