# orbit-local

[Orbit](https://github.com/deltaomega02/orbit)(졸업작품)의 코디 도메인을 **Kotlin + Spring Boot로 이식한 것**입니다.

원본은 Django REST로 만들었고 잘 돌아갔지만, 나중에 코드를 다시 읽으면서 **네 가지가 빠져 있다는 걸 알았습니다** — 인증, 트랜잭션 경계, 테스트, 그리고 목록 조회의 N+1. 이 저장소는 같은 도메인을 다시 만들면서 그 넷을 먼저 넣은 결과입니다. 문법 연습이 아니라, **제가 놓친 것이 다른 프레임워크에서는 어떻게 다뤄지는지 확인하는 것**이 목적이었습니다.

원본에서 가장 잘한 판단이라고 생각하는 **중복 추천 → HTTP 409 + `retry: true`** 계약은 그대로 가져왔습니다.

```
Kotlin 2.2 · Spring Boot 3.5 · Spring Security · JWT(jjwt) · Spring Data JPA · H2(기본) / MySQL(프로파일) · JUnit 5
```

## 실행

```bash
./gradlew test          # 테스트 46개
./gradlew bootRun       # http://localhost:8080 (H2 인메모리)
```

MySQL로 붙이려면 `--args='--spring.profiles.active=mysql'`.

JWT 시크릿은 `ORBIT_JWT_SECRET` 환경변수로 주입합니다. 설정하지 않으면 `dev-only-insecure-...`라는 개발용 기본값이 쓰이는데, **이름 그대로 개발용**이고 운영에 올라가면 값만 봐도 사고인 걸 알 수 있게 해 뒀습니다. 실제 시크릿은 저장소에 넣지 않습니다.

```bash
ORBIT_JWT_SECRET='...32바이트 이상...' ./gradlew bootRun
```

## API

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | — | 가입. 이메일 중복이면 **409** |
| `POST` | `/api/auth/login` | — | 액세스(15분) + 리프레시(14일) 토큰 발급 |
| `POST` | `/api/auth/refresh` | — | 리프레시 토큰으로 액세스 토큰 재발급 |
| `POST` | `/api/clothes` | 필요 | 의류 등록 |
| `GET` | `/api/clothes` | 필요 | 의류 목록 (페이지네이션, 기본 20건) |
| `GET` | `/api/clothes/{id}` | 필요 | 의류 상세 |
| `PATCH` | `/api/clothes/{id}` | 필요 | 의류 수정(보낸 필드만) |
| `DELETE` | `/api/clothes/{id}` | 필요 | 의류 삭제. 코디에 사용 중이면 **409** |
| `POST` | `/api/coordinations` | 필요 | 코디 생성. 중복이면 **409** |
| `GET` | `/api/coordinations/today` | 필요 | 오늘 만든 코디 목록 |

인증이 필요한 경로는 `Authorization: Bearer <액세스 토큰>` 헤더를 받습니다. **소유자는 요청이 지정하는 것이 아니라 서버가 토큰에서 읽습니다** — 요청 본문 어디에도 `ownerId`를 적을 자리가 없습니다.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@orbit.test","password":"orbit-test-1234"}' | jq -r .accessToken)

curl -X POST localhost:8080/api/coordinations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"출근룩","clothesIds":[1,2]}'
```

중복이면:

```json
{ "error": "duplicate", "retry": true, "clothesIds": [1, 2] }
```

## 인증 — 원본에서 무엇이 잘못됐고 여기서 무엇을 바꿨나

이 저장소를 만든 가장 큰 이유입니다. 원본 Django의 인증은 이랬습니다.

```python
# 11개 뷰에 그대로 복붙돼 있던 4줄
token = request.headers.get("Authorization", "").replace("Token ", "")
user = User.objects.filter(email=token).first()
if not user:
    return Response(status=401)
```

**토큰이 이메일 문자열 그 자체였습니다.** `Authorization: Token someone@gmail.com` 한 줄이면 그 사람이 됐습니다.

| | 원본 Orbit (Django) | orbit-local (Spring) |
|---|---|---|
| 토큰 | 이메일 평문 | HS256 서명 JWT |
| 위조 | 이메일만 알면 누구든 됨 | 서버 키 서명 없으면 401 — 테스트로 고정 |
| 만료 | 없음. 한 번 새면 영구 | 액세스 15분 / 리프레시 14일 |
| 검증 위치 | 뷰 11곳에 복붙 | `OncePerRequestFilter` 한 곳 |
| 누락 위험 | 한 뷰에서 빠지면 무방비 | 기본값이 `authenticated()`, 열어줄 경로만 나열 |
| 비밀번호 | (소셜 로그인만, 자체 계정 없음) | BCrypt 해시. 평문 저장 없음 — 테스트로 고정 |
| Google ID 토큰 | 서명 검증 없이 payload 신뢰 | (미구현. 붙인다면 서명·aud·iss 검증 필수) |
| 소유권 조건 | `user=request.user`를 쿼리마다 손으로 | 리포지토리 시그니처가 `ownerId`를 요구 |

컨트롤러에는 인증 코드가 **한 줄도 없습니다.** `@AuthenticationPrincipal user: AuthenticatedUser` 하나로 끝납니다. 원본에서 복붙 4줄 × 11곳이 하던 일이 필터 하나로 모인 것이 이 이식의 핵심입니다.

### 판단한 것들

**jjwt를 골랐습니다.** nimbus-jose-jwt는 JWE·JWK 세트·OIDC까지 다루는 범용 구현이라 지금 필요한 것(대칭키 HS256 서명 하나)에 비해 표면이 넓습니다. jjwt 0.12는 `verifyWith(key)`로 파싱 시 알고리즘이 키에 고정되어 `alg=none`·알고리즘 혼동 공격이 API 수준에서 막힙니다.

**토큰 종류를 클레임에 박고 검증합니다.** 액세스와 리프레시는 같은 키로 서명되므로 서명만으로는 구분되지 않습니다. 종류를 확인하지 않으면 (1) 리프레시 토큰이 그대로 14일짜리 API 키가 되고, (2) 액세스 토큰으로 무한히 갱신할 수 있어 "15분"이라는 노출 상한이 사라집니다. 양방향 모두 테스트가 있습니다.

**리프레시 토큰을 회전시키지 않습니다.** 폐기 저장소 없이 새 리프레시를 내주면 만료가 무한히 밀려 14일 상한이 사실상 없어집니다. 회전을 하려면 이전 토큰을 무효화할 저장소(jti 블랙리스트 등)가 먼저 필요하고, 그건 지금 범위 밖이라 상한을 지키는 쪽을 택했습니다. **즉 현재 구조에는 강제 로그아웃이 없습니다.** 알고 남겨둔 빈칸입니다.

**액세스 토큰 검증에 DB를 보지 않습니다.** 요청마다 사용자 조회를 하면 무상태의 이점이 사라집니다. 대신 탈퇴·차단이 즉시 반영되지 않으므로, **반영 지연의 상한이 곧 액세스 토큰 만료(15분)**입니다. 그래서 짧게 잡았습니다. 재발급은 요청량이 적어 거기서만 사용자 존재를 확인합니다.

**남의 리소스에는 403이 아니라 404입니다.** 403은 "그 id는 실재한다"를 알려줍니다. id를 1부터 훑으면 남이 옷을 몇 벌 가졌는지까지 셀 수 있습니다. 권한이 없는 쪽에서는 없는 것과 구별되지 않아야 합니다. 인증 자체가 없을 때만 401로 구분합니다(그건 존재 여부와 무관한 정보입니다).

**로그인 실패 응답을 하나로 통일했습니다.** "이메일이 없습니다"와 "비밀번호가 틀렸습니다"를 구분해 주면 로그인 API가 곧 가입 여부 조회 API가 됩니다. 두 응답이 바이트 단위로 같은지 테스트로 확인합니다.

## 설계 메모

**중복 판정을 서버가 한다.** 추천 엔진은 확률적이라 같은 옷장·같은 조건이면 같은 조합을 반복합니다. 프롬프트에 "같은 걸 내지 마라"를 넣는 건 보장이 아니라 요청이라, 무시될 수 있습니다. 그래서 서버가 당일 코디를 **의류 id 집합으로 비교**해 검증하고, 중복이면 409로 거부합니다. 순서만 다른 조합도 같은 것으로 봅니다.

**N:M을 중간 엔티티로.** 옷은 겹쳐 입기 때문에 코디–의류 관계에 **레이어 순서**라는 속성이 붙습니다. `@ManyToMany`로는 표현이 안 돼서 `CoordinationItem`을 명시적으로 두고, `(coordination_id, clothes_id)`에 유니크 제약을 걸었습니다.

**User는 엔티티, 소유권은 id 참조.** `Clothes.ownerId`에 `@ManyToOne User`를 걸지 않았습니다. 소유권 격리에 필요한 건 id 비교뿐인데 연관을 걸면 조회마다 쓰지도 않을 User를 끌고 오게 되고, 무엇보다 User와 Clothes는 다른 애그리거트라 id로 참조하는 편이 경계를 흐리지 않습니다. 대가로 DB FK가 없어 사용자 삭제 기능을 붙이는 시점에는 정리 로직이 필요합니다.

**트랜잭션 경계.** 코디 1건과 아이템 N건은 하나의 애그리거트로 묶어 `@Transactional` 안에서 저장합니다. Django 버전에서는 이게 없어서 중간에 실패하면 아이템이 비어 있는 코디가 남을 수 있었습니다. → `PersistenceBehaviorTest`

**N+1을 테스트로 고정.** 목록 조회는 fetch join으로 한 번에 읽습니다. 그런데 "지금 안 나는 것"과 "앞으로도 안 나는 것"은 다르므로, Hibernate 통계로 **실행된 쿼리 수가 정확히 1인지 검증**합니다. 코디 3건·아이템 7건을 읽어도 1회입니다. `open-in-view: false`로 둬서 뷰에서 지연 로딩이 몰래 열리지 않게 했습니다.

**페이지네이션은 "설정했다"가 아니라 "동작한다"를 확인한다.** 원본 Django에는 `REST_FRAMEWORK.PAGE_SIZE = 20` 설정이 있었지만, 뷰가 함수형(`@api_view`)이라 페이지네이션 클래스가 개입할 자리가 없었고 **한 번도 동작하지 않았습니다.** 목록은 늘 전체를 반환했습니다. 여기서는 21건을 넣고 1페이지가 20건인지, 2페이지가 1건인지 테스트로 확인합니다. `?size=1000000`으로 전체를 긁어가지 못하게 상한(100)도 뒀습니다.

**Page를 그대로 직렬화하지 않는다.** Spring의 `Page`를 응답으로 내보내면 내부 구조(`pageable`, `sort`, `numberOfElements`…)가 그대로 API 스키마가 되어 라이브러리를 올릴 때 클라이언트가 깨집니다. `PageResponse`로 필요한 필드만 노출합니다.

**Clock 주입.** "오늘"의 경계가 시스템 시각에 묶여 있으면 자정 근처에서 테스트가 흔들립니다. `Clock`을 빈으로 주입해 고정할 수 있게 뒀습니다. 같은 `Clock`을 JWT 발급·만료 판정에도 쓰기 때문에, **만료된 토큰을 15분 기다리지 않고 만들 수 있습니다** — 과거로 고정한 Clock으로 발급하면 끝입니다.

## 테스트

```
코디 생성 — 중복 판정과 소유권          6개
영속성 계층 — 트랜잭션 경계와 N+1        3개
코디 API — 409 재시도 계약             6개
인증 API — 가입·로그인·재발급            9개
토큰 검증 — 위조·만료·종류 혼용          9개
의류 API — CRUD·소유권 격리·페이지네이션  13개
                                  ─────
                                   46개
```

인증 테스트는 `@WithMockUser`로 컨텍스트를 주입하지 않고 **실제 가입·로그인을 거쳐 진짜 토큰을 받습니다.** 컨텍스트를 주입하면 필터·서명·만료 검증이 통째로 건너뛰어져서, 정작 검증하려는 인증 경로가 테스트에서 빠지기 때문입니다.

토큰 쪽은 공격자 입장에서 다뤄봅니다 — 서명 한 글자 뒤집기, 페이로드의 `sub`를 남의 id로 바꾸고 서명은 그대로 두기(권한 상승 시도), 다른 시크릿으로 서명한 위조 토큰, 만료된 토큰, 리프레시↔액세스 혼용 양방향. 원본이었다면 이 중 첫 번째부터 그냥 통했습니다.

소유권 격리(`남의 옷으로는 코디를 만들 수 없다`, `남의 의류는 조회·수정·삭제되지 않는다`)를 테스트로 박아둔 이유는, 원본에서 `user=user` 조건을 뷰 15곳에 손으로 반복하고 있었기 때문입니다. 하나라도 빠지면 IDOR인데 그걸 잡아줄 장치가 없었습니다.

## Django → Spring 대응 메모

이식하면서 확인한 것들입니다.

| Django/DRF | Spring Boot | 원본에서 겪은 것 |
|---|---|---|
| 뷰마다 반복한 인증 4줄 | `OncePerRequestFilter` + `SecurityContextHolder` | 11개 뷰에 복붙돼 있었음 |
| `request.user` | `@AuthenticationPrincipal` | 토큰 파싱을 뷰가 직접 함 |
| 이메일 = 토큰 | 서명·만료 있는 JWT | 서명이 없어 위조 개념 자체가 없었음 |
| `check_password` (미사용) | `BCryptPasswordEncoder` | 자체 계정이 없어 비밀번호 정책이 아예 없었음 |
| `permission_classes` (뷰마다 지정) | `SecurityFilterChain` (기본 차단 + 예외 나열) | 지정을 빠뜨리면 그대로 공개됨 |
| `REST_FRAMEWORK.PAGE_SIZE` | `Pageable` + `Page` | 설정만 있고 함수형 뷰라 동작 안 함 |
| `transaction.atomic()` (누락) | `@Transactional` | 아이템 없는 코디가 남을 수 있었음 |
| `prefetch_related` | fetch join / `@EntityGraph` | 추천 로직엔 걸고 목록 뷰엔 빠뜨림 |
| Serializer (직렬화+검증+저장 겸업) | DTO + `@Valid` + `@Service` | serializer 안에서 외부 API를 호출하고 있었음 |
| 뷰마다 try/except | `@RestControllerAdvice` | 응답 모양이 조금씩 달라져 클라이언트가 키 3개를 fallback |
| 모듈 싱글톤 | DI 빈 | 교체가 안 돼 테스트가 어려웠음 |

## 다음

- [x] Spring Security + JWT (액세스/리프레시) — 원본의 가장 큰 부채
- [x] 의류 CRUD 엔드포인트 (페이지네이션 포함)
- [ ] 리프레시 토큰 회전 + 폐기 저장소 (= 강제 로그아웃). 지금은 없는 것을 알고 남겨둔 빈칸
- [ ] Google ID 토큰 검증(서명·`aud`·`iss`) 후 소셜 로그인 연결
- [ ] 데스크톱 클라이언트 연결 (로컬 단독 실행용)
