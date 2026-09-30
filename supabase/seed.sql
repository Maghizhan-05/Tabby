-- Tabby / SpendTracker default category seed
-- Inserts the default categories for the currently authenticated user.
-- Run while authenticated, or adapt the user_id as needed.

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
