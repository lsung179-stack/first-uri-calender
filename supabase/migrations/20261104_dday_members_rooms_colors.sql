-- D-day(anniversaries) 를 일정처럼 — 함께할 멤버·여러 방·'나만 이 색으로 보기' + 연결 일정과 서로 맞추기 (2026-11-04)
-- 사용자: "디데이를 생성할때 이방만/모든방/방 선택 … 함께 멤버 선택 … 선택한 멤버의 프로필에만 표시 …
--          색상은 본인이 원하는 색상을 나만보기를 통해 적용"
-- ⚠️ 기존 행은 고치지 않는다(member_keys null = 예전처럼 모든 멤버 탭에 보임).

-- 1) 함께할 멤버(실제 = user id, 가상 = 'v-<members.id>') · 여러 방에 함께 만든 D-day 묶음
alter table public.anniversaries add column if not exists member_keys text[];
alter table public.anniversaries add column if not exists group_id uuid;
create index if not exists anniversaries_group_idx on public.anniversaries(group_id) where group_id is not null;
create index if not exists anniversaries_event_idx on public.anniversaries(event_id) where event_id is not null;

-- 2) 사람마다 D-day 색('나만 이 색으로 보기') — 방 멤버는 읽을 수 있고(연결 일정 다시 만들 때 각자 색 유지), 자기 줄만 쓴다
create table if not exists public.anniversary_colors (
  ann_id uuid not null references public.anniversaries(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  color text not null check (color ~ '^(#[0-9A-Fa-f]{6}|c-[a-z]{2,20})$'),
  bar_style text not null default 'solid' check (bar_style in ('solid', 'outline')),
  updated_at timestamptz not null default now(),
  primary key (ann_id, user_id)
);
alter table public.anniversary_colors enable row level security;

drop policy if exists anniversary_colors_select_member on public.anniversary_colors;
create policy anniversary_colors_select_member on public.anniversary_colors for select using (
  exists (select 1 from public.anniversaries a join public.members m on m.room_id = a.room_id
          where a.id = anniversary_colors.ann_id and m.user_id = auth.uid()));
drop policy if exists anniversary_colors_insert_own on public.anniversary_colors;
create policy anniversary_colors_insert_own on public.anniversary_colors for insert with check (
  user_id = auth.uid() and exists (select 1 from public.anniversaries a join public.members m on m.room_id = a.room_id
          where a.id = anniversary_colors.ann_id and m.user_id = auth.uid()));
drop policy if exists anniversary_colors_update_own on public.anniversary_colors;
create policy anniversary_colors_update_own on public.anniversary_colors for update using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists anniversary_colors_delete_own on public.anniversary_colors;
-- 지우기: 자기 줄 + D-day 를 만든 사람은 그 D-day 의 모든 줄('모두에게 적용' = 각자 고른 색을 지우고 새 색으로)
create policy anniversary_colors_delete_own on public.anniversary_colors for delete using (
  user_id = auth.uid() or exists (select 1 from public.anniversaries a where a.id = anniversary_colors.ann_id and a.created_by = auth.uid()));
revoke all on public.anniversary_colors from anon;
grant select, insert, update, delete on public.anniversary_colors to authenticated;

-- 3) 연결 일정(anniversaries.event_id, 함께면 같은 shared_id 의 여러 줄)을 캘린더(웹·2.0 어디서든)에서 고치거나 지울 때 D-day 도 맞춘다.
--    · 연결 줄의 제목·날짜·매년 반복이 바뀌면 → D-day 도 같은 값(그 방 D-day 만)
--    · 어느 줄이든 색·테두리가 바뀌면 → 그 줄 주인의 '내 D-day 색'(anniversary_colors) — 일정의 '나만 이 색으로 보기'와 같음
--    · 연결 줄을 지우면 → 같은 shared_id 의 남은 줄로 연결을 옮기고, 남은 줄이 없으면 '캘린더에 표시'만 끈다(D-day 는 남김).
--      D-day 까지 지울지는 앱이 먼저 물어보고 D-day 를 지운다(만든 사람만).
create or replace function public.anniv_sync_from_event() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  a record;
  sib uuid;
begin
  if tg_op = 'DELETE' then
    for a in select id from anniversaries where event_id = old.id loop
      sib := null;
      if old.shared_id is not null then
        select e.id into sib from events e where e.shared_id = old.shared_id and e.id <> old.id order by (e.user_id = (select created_by from anniversaries where id = a.id)) desc, e.created_at limit 1;
      end if;
      if sib is not null then
        update anniversaries set event_id = sib where id = a.id;
      else
        update anniversaries set on_cal = false where id = a.id;
      end if;
    end loop;
    return old;
  end if;

  -- 이 줄이 연결 줄이면 공통 항목을 D-day 로
  if new.title is distinct from old.title or new.date is distinct from old.date
     or new.repeat_type is distinct from old.repeat_type or new.recurring_yearly is distinct from old.recurring_yearly then
    update anniversaries x set
      title = coalesce(nullif(left(btrim(coalesce(new.title, '')), 40), ''), x.title),
      date = new.date,
      repeat_yearly = case when x.kind = 'countdown'
                           then (new.repeat_type = 'yearly' or (new.recurring_yearly and coalesce(new.repeat_type, 'none') = 'none'))
                           else x.repeat_yearly end
    where x.event_id = new.id;
  end if;

  -- 색·테두리 → 그 줄 주인의 내 D-day 색(가상 멤버 줄은 건너뜀)
  if (new.color is distinct from old.color or new.bar_style is distinct from old.bar_style)
     and new.virtual_member_id is null and new.color ~ '^(#[0-9A-Fa-f]{6}|c-[a-z]{2,20})$' then
    insert into anniversary_colors (ann_id, user_id, color, bar_style, updated_at)
    select x.id, new.user_id, new.color, case when new.bar_style = 'outline' then 'outline' else 'solid' end, now()
    from anniversaries x
    where x.event_id = new.id
       or (new.shared_id is not null and x.event_id in (select e.id from events e where e.shared_id = new.shared_id))
    on conflict (ann_id, user_id) do update set color = excluded.color, bar_style = excluded.bar_style, updated_at = now();
  end if;
  return new;
end $$;

drop trigger if exists events_anniv_sync on public.events;
create trigger events_anniv_sync after update on public.events for each row execute function public.anniv_sync_from_event();
drop trigger if exists events_anniv_unlink on public.events;
create trigger events_anniv_unlink before delete on public.events for each row execute function public.anniv_sync_from_event();
