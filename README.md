# Tabby for Android

Android rebuild of the Tabby expense tracker. Shares the Supabase backend with
the iOS app — the schema, RLS policies and sync semantics are the iOS app's, and
this project does not change them.

## Build

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
./gradlew clean assembleDebug test
```

Gradle comes from the checked-in wrapper with a pinned `distributionSha256Sum`;
do not install it from a package manager.

## Configuration

Supabase credentials are read from `local.properties`, which is gitignored and
must never be committed:

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
supabase.url=https://YOUR_PROJECT_REF.supabase.co
supabase.publishableKey=YOUR_PUBLISHABLE_KEY
```

Use the **publishable** key, never the service-role key. With these absent the
app still builds and runs; the auth router resolves to signed-out and says the
app is not configured.

The OAuth redirect `com.maghizhan.tabby://auth-callback` must be on the Supabase
Auth redirect allow-list.

## Known limitation: remote deletions stop converging above 1000 rows

**This is a deliberate, accepted trade-off.** It is the one behaviour in the sync
layer that is knowingly incomplete, so it is documented here as well as in
`CompleteSnapshot.kt`.

Deleting a record locally is explicit: it writes a tombstone that is pushed to
the backend. But learning that a record was deleted *on another device* is
inferred from **absence** — the row is simply no longer in what the server
returns. Acting on absence is only safe against a result set proven to be
complete, because an incomplete result is indistinguishable from a deletion.

Completeness is proven only by a **single** request whose exact server-side count
matches the distinct rows received. Across multiple requests it cannot be proven:
delete one early row and insert one later row between two pages and the total is
unchanged while one row is never returned, and duplicate ids across overlapping
pages forge the same false proof.

So once one entity type holds more than **1000 rows for a single account**:

| Behaviour | Still works? |
|---|---|
| Additions and updates, both directions | ✅ Yes |
| Deletions made on **this** device, propagating to the backend | ✅ Yes (explicit tombstones) |
| Deletions made on **another** device, propagating to this one | ❌ **No** |

The user-visible effect: a record deleted on another device lingers on this one
until the account drops back under the threshold.

This fails in the safe direction. The alternative — trusting an unproven snapshot
— silently destroys records the user still has, and lost financial data is
unrecoverable. A lingering row is visible and fixable; a deleted one is not.

Closing it properly requires a server-side consistent snapshot (an RPC reading
the whole set in one statement, or a keyset/watermark strategy that cannot
shift). Both are backend changes, which this project does not make.

## Architecture notes

- **Ownership is session-derived, never a parameter.** Repositories read
  `user_id` from the live session, so a stale local owner id cannot be replayed
  against the backend.
- **A sync cycle is bound to a session identity** (owner id + monotonic
  generation) and revalidated immediately before each remote mutation and each
  local acknowledgement. The generation matters because signing out of an account
  and back into it produces the same owner id with different tokens.
- **Pushes are acknowledged with compare-and-set** on id, exact owner, uploaded
  revision and expected state, so an edit made during an upload is not cleared.
- **Local writes go through the stores** (`ExpenseStore`, `CategoryStore`,
  `FriendStore`), never raw DAO upserts: they check the existing row's owner,
  validate money, and bump the revision inside one transaction.
- **Money is `BigDecimal`**, persisted as a plain decimal string and uploaded as
  one, so values reach Postgres `numeric` without passing through a float.
- **Timestamps are microseconds**, matching Postgres `timestamptz`.
