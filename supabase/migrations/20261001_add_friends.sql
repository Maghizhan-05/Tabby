-- Friends use the locally-created UUID as their canonical cloud primary key.
create table if not exists public.friends (
    id uuid primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    name text not null,
    they_owe_us numeric(12, 2) not null default 0 check (they_owe_us >= 0),
    we_owe_them numeric(12, 2) not null default 0 check (we_owe_them >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists friends_user_name_idx
    on public.friends (user_id, name);

alter table public.friends enable row level security;

drop policy if exists "Friends select own" on public.friends;
create policy "Friends select own"
    on public.friends for select
    using (auth.uid() = user_id);

drop policy if exists "Friends insert own" on public.friends;
create policy "Friends insert own"
    on public.friends for insert
    with check (auth.uid() = user_id);

drop policy if exists "Friends update own" on public.friends;
create policy "Friends update own"
    on public.friends for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Friends delete own" on public.friends;
create policy "Friends delete own"
    on public.friends for delete
    using (auth.uid() = user_id);
