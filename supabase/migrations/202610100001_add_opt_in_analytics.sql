-- First-party, opt-in analytics. No free-form event schema is exposed to the
-- client, and no free-form payload is accepted by the server either: the public
-- privacy claim ("never includes notes, names, email, or exact amounts") is
-- enforced by the constraints below, not only by the app's sealed event type.
--
-- Re-runnable end to end. This migration is applied by pasting it into the
-- Supabase SQL editor, which is exactly where a half-applied run gets retried.
create table if not exists public.analytics_events (
    id uuid primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    name text not null,
    occurred_at timestamptz not null,
    app_version text not null,
    props jsonb not null default '{}'::jsonb
);

-- Constraints are dropped first so a retry, or a later allowlist change,
-- replaces them instead of failing on an existing definition.
alter table public.analytics_events
    drop constraint if exists analytics_events_name_allowlist;
alter table public.analytics_events
    add constraint analytics_events_name_allowlist check (name in (
        'app_opened', 'screen_viewed', 'expense_logged', 'expense_edited',
        'expense_deleted', 'category_created', 'category_deleted',
        'friend_created', 'friend_updated', 'period_changed', 'widget_placed',
        'widget_tapped', 'widget_period_cycled', 'sync_completed', 'sign_in',
        'account_deleted'
    ));

-- Key allowlist: a compromised or out-of-date client cannot invent a property.
-- Every permitted key is a bucket, an enum or a count -- never user-entered
-- text and never an exact amount.
--
-- Expressed with the jsonb key-delete operator rather than a subquery: CHECK
-- constraints may not contain subqueries, and `jsonb - text[]` is immutable.
-- Removing every allowed key must leave the empty object.
alter table public.analytics_events
    drop constraint if exists analytics_events_props_allowlist;
alter table public.analytics_events
    add constraint analytics_events_props_allowlist check (
        props - array[
            'cold_start', 'screen', 'has_note', 'amount_bucket', 'period',
            'shape', 'duration_ms', 'pushed', 'pulled', 'provider'
        ] = '{}'::jsonb
    );

-- Size cap. `length(props::text)` rather than pg_column_size(), which is STABLE
-- and therefore rejected inside a CHECK constraint; the serialised length is
-- the bound that matters anyway. Every legitimate payload is well under 200
-- bytes, so free text cannot hide under this ceiling.
alter table public.analytics_events
    drop constraint if exists analytics_events_props_size;
alter table public.analytics_events
    add constraint analytics_events_props_size check (length(props::text) <= 512);

create index if not exists analytics_events_user_time_idx
    on public.analytics_events (user_id, occurred_at desc);
create index if not exists analytics_events_occurred_at_idx
    on public.analytics_events (occurred_at);

alter table public.analytics_events enable row level security;

drop policy if exists "Analytics insert own" on public.analytics_events;
create policy "Analytics insert own"
    on public.analytics_events for insert
    with check (auth.uid() = user_id);

-- Clients deliberately receive no select/update/delete policy.

-- Supabase Cron / pg_cron retains optional analytics for at most 90 days.
-- If the SQL editor rejects the extension line, enable pg_cron from
-- Database -> Extensions and re-run: everything here tolerates a retry.
create extension if not exists pg_cron with schema pg_catalog;

-- Unschedule first: cron.schedule raises on a duplicate job name, which would
-- abort a retry partway through. Wrapped because unschedule also raises when
-- the job does not exist yet, which is the normal first-run case.
do $$
begin
    perform cron.unschedule('purge-analytics-events-90d');
exception
    when others then null;
end;
$$;

select cron.schedule(
    'purge-analytics-events-90d',
    '17 3 * * *',
    $$delete from public.analytics_events
      where occurred_at < now() - interval '90 days';$$
);
