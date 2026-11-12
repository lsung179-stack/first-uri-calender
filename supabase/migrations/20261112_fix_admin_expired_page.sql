-- 관리자 페이지 '만료 구독 목록'(admin_expired_page) 오류 고침 (2026-11-12 전체 점검에서 발견)
-- 증상: 400 "column t.expires_at must appear in the GROUP BY clause or be used in an aggregate function"
-- 원인: json_agg 를 쓰는 같은 단계에서 order by · limit 를 걸었다 → 정렬·자르기를 안쪽 조회로 옮김.
-- 관리자 확인·반환 모양(ok·total·rows)·권한은 그대로.
create or replace function public.admin_expired_page(p_days integer default 30, p_limit integer default 10, p_offset integer default 0)
 returns json
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare v_admin uuid := auth.uid(); v_total int;
begin
  if v_admin IS DISTINCT FROM /*sec_fix_<>*/ '3bc13a8b-618d-4d47-abea-1eec4d682ec0'::uuid then
    return json_build_object('ok', false, 'error', '관리자 권한이 없습니다');
  end if;
  select count(*) into v_total from (
    select distinct s.user_id from public.subscriptions s
    where s.expires_at <= now() and s.expires_at > now()-(p_days||' days')::interval
      and not exists(select 1 from public.subscriptions s2
        where s2.user_id=s.user_id and s2.status='active' and s2.expires_at>now())
  ) x;
  return json_build_object('ok', true, 'total', v_total, 'rows', coalesce((
    select json_agg(row_to_json(u))
    from (
      select t.*
      from (
        select distinct on (s.user_id)
               s.id, s.user_id, s.plan_type, s.expires_at, s.is_manual, s.manual_reason,
               p.name, p.email,
               floor(extract(epoch from (now()-s.expires_at))/86400)::int as days_ago
        from public.subscriptions s
        left join public.profiles p on p.id=s.user_id
        where s.expires_at <= now() and s.expires_at > now()-(p_days||' days')::interval
          and not exists(select 1 from public.subscriptions s2
            where s2.user_id=s.user_id and s2.status='active' and s2.expires_at>now())
        order by s.user_id, s.expires_at desc
      ) t
      order by t.expires_at desc
      limit p_limit offset p_offset
    ) u
  ), '[]'::json));
end; $function$;
