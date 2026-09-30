-- Tabby / SpendTracker default category seed
--
-- NOTE: You normally DO NOT need to run this. The app seeds the 8 default
-- categories per-user automatically on first sign-in (locally, then synced
-- under your auth.uid()). Running this in the Supabase SQL editor will FAIL
-- because auth.uid() is NULL there (you are not signed in as an app user),
-- and the owner-scoped RLS insert policy correctly rejects a NULL user_id.
-- That failure is expected and proves RLS is working.
--
-- This file is only for optionally pre-populating a SPECIFIC known user:
-- replace auth.uid() below with that user's UUID (from Authentication -> Users)
-- and run it. For normal use, ignore this file and just sign in through the app.

insert into public.categories (user_id, name, is_default, sort_order)
values
    (auth.uid(), 'Food', true, 0),
    (auth.uid(), 'Transport', true, 1),
    (auth.uid(), 'Groceries', true, 2),
    (auth.uid(), 'Bills', true, 3),
    (auth.uid(), 'Shopping', true, 4),
    (auth.uid(), 'Entertainment', true, 5),
    (auth.uid(), 'Health', true, 6),
    (auth.uid(), 'Other', true, 7)
on conflict (user_id, name) do nothing;
