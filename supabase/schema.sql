-- Tabby / SpendTracker Supabase schema
-- Run this in the Supabase SQL editor after creating your project.

-- ---------------------------------------------------------------------------
-- profiles
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    email text not null,
    display_name text,
    created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

create policy "Profiles are viewable by owner"
    on public.profiles for select
    using (auth.uid() = id);

create policy "Users can insert own profile"
    on public.profiles for insert
    with check (auth.uid() = id);

create policy "Users can update own profile"
    on public.profiles for update
    using (auth.uid() = id);

create policy "Users can delete own profile"
    on public.profiles for delete
    using (auth.uid() = id);

-- ---------------------------------------------------------------------------
-- categories
-- ---------------------------------------------------------------------------
create table if not exists public.categories (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    name text not null,
    is_default boolean not null default false,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    unique (user_id, name)
);

alter table public.categories enable row level security;

create policy "Categories select own"
    on public.categories for select
    using (auth.uid() = user_id);

create policy "Categories insert own"
    on public.categories for insert
    with check (auth.uid() = user_id);

create policy "Categories update own"
    on public.categories for update
    using (auth.uid() = user_id);

create policy "Categories delete own"
    on public.categories for delete
    using (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- expenses
-- ---------------------------------------------------------------------------
create table if not exists public.expenses (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    amount numeric(12, 2) not null,
    category_name text not null,
    note text,
    date timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    remote_id text
);

create index if not exists expenses_user_date_idx
    on public.expenses (user_id, date desc);

alter table public.expenses enable row level security;

create policy "Expenses select own"
    on public.expenses for select
    using (auth.uid() = user_id);

create policy "Expenses insert own"
    on public.expenses for insert
    with check (auth.uid() = user_id);

create policy "Expenses update own"
    on public.expenses for update
    using (auth.uid() = user_id);

create policy "Expenses delete own"
    on public.expenses for delete
    using (auth.uid() = user_id);
