-- ⚠️ 아직 라이브 적용 안 됨(2026-11-26 — 함수를 지우고 다시 만드는 단계라 승인 대기). 적용하면 이 줄과 파일 이름의 _PENDING 을 지울 것.
-- 관리자 회원 검색(수동 구독 부여·코인 지급 등): 2026-11-26 사용자 "현이라고 검색해도 안 나오네"
-- ① 한 글자 이름(예: '현')도 찾게 — 2글자 미만은 빈 결과였다(화면도 2글자 미만이면 검색을 안 했다)
-- ② 이름이 검색어와 똑같은 사람 → 검색어로 시작하는 사람 → 나머지(최근 가입순)
-- ③ 같은 이름이 여럿이면 구분되게 가입일·로그인 방법도 돌려준다(돌려주는 칸이 바뀌어 지우고 다시 만든다)
-- 관리자 확인(is distinct from)·권한(public·anon 제외, authenticated·service_role 실행)은 그대로.
drop function if exists public.admin_search_users(text, integer);
create function public.admin_search_users(p_q text, p_limit integer default 20)
returns table(id uuid, name text, email text, avatar_url text, joined_at timestamptz, provider text)
language plpgsql
stable security definer
set search_path to ''
as $function$
declare v_q text; v_esc text;
begin
  if auth.uid() is distinct from '3bc13a8b-618d-4d47-abea-1eec4d682ec0'::uuid then
    raise exception 'forbidden' using errcode = '42501';
  end if;
  v_q := trim(coalesce(p_q, ''));
  if length(v_q) < 1 then return; end if;
  v_esc := replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_');
  return query
    select p.id, p.name, coalesce(nullif(u.email, ''), p.email)::text, p.avatar_url,
           p.created_at, (u.raw_app_meta_data->>'provider')::text
      from public.profiles p left join auth.users u on u.id = p.id
     where p.name ilike '%' || v_esc || '%' or coalesce(u.email, p.email) ilike '%' || v_esc || '%'
     order by (lower(trim(coalesce(p.name, ''))) = lower(v_q)) desc,
              (coalesce(p.name, '') ilike v_esc || '%') desc,
              p.created_at desc nulls last
     limit greatest(1, least(coalesce(p_limit, 20), 50));
end $function$;

revoke execute on function public.admin_search_users(text, integer) from public, anon;
grant execute on function public.admin_search_users(text, integer) to authenticated, service_role;
