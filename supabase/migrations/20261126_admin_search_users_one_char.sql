-- 관리자 회원 검색: 한 글자 이름(예: '현')도 찾게 — 2글자 미만은 빈 결과였다(2026-11-26 사용자 "현이라고 검색해도 안 나오네").
-- 이름이 검색어와 똑같은 사람 → 이름이 검색어로 시작하는 사람 → 나머지(최근 가입순). 관리자 확인·권한은 그대로. (라이브 적용 2026-11-26)
create or replace function public.admin_search_users(p_q text, p_limit integer default 20)
returns table(id uuid, name text, email text, avatar_url text)
language plpgsql
stable security definer
set search_path to ''
as $function$
declare v_q text; v_pat text;
begin
  if auth.uid() is distinct from '3bc13a8b-618d-4d47-abea-1eec4d682ec0'::uuid then
    raise exception 'forbidden' using errcode = '42501';
  end if;
  v_q := trim(coalesce(p_q, ''));
  if length(v_q) < 1 then return; end if;
  v_pat := '%' || replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_') || '%';
  return query
    select p.id, p.name, coalesce(u.email, p.email)::text, p.avatar_url
      from public.profiles p left join auth.users u on u.id = p.id
     where p.name ilike v_pat or coalesce(u.email, p.email) ilike v_pat
     order by (lower(trim(coalesce(p.name, ''))) = lower(v_q)) desc,
              (lower(coalesce(p.name, '')) like lower(replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_')) || '%') desc,
              p.created_at desc nulls last
     limit greatest(1, least(coalesce(p_limit, 20), 50));
end $function$;

revoke execute on function public.admin_search_users(text, integer) from public, anon;
