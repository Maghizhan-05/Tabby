-- Run this once in the Supabase SQL Editor before releasing note sync.
alter table public.expenses
add column if not exists note text;
