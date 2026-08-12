# orbit-local

[Orbit](https://github.com/deltaomega02/orbit)(졸업작품)의 코디 도메인을 **Kotlin + Spring Boot로 이식한 것**입니다.

원본은 Django REST로 만들었고 잘 돌아갔지만, 나중에 코드를 다시 읽으면서 **세 가지가 빠져 있다는 걸 알았습니다** — 트랜잭션 경계, 테스트, 그리고 목록 조회의 N+1. 이 저장소는 같은 도메인을 다시 만들면서 그 셋을 먼저 넣은 결과입니다. 문법 연습이 아니라, **제가 놓친 것이 다른 프레임워크에서는 어떻게 다뤄지는지 확인하는 것**이 목적이었습니다.

원본에서 가장 잘한 판단이라고 생각하는 **중복 추천 → HTTP 409 + `retry: true`** 계약은 그대로 가져왔습니다.

```
Kotlin 2.2 · Spring Boot 3.5 · Spring Data JPA · H2(기본) / MySQL(프로파일) · JUnit 5
```

## 실행

```bash
./gradlew test          # 테스트 13개
./gradlew bootRun       # http://localhost:8080 (H2 인메모리)
```

MySQL로 붙이려면 `--args='--spring.profiles.active=mysql'`.

## API

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/coordinations` | 코디 생성. 중복이면 **409** |
| `GET` | `/api/coordinations/today` | 오늘 만든 코디 목록 |

요청은 `X-Owner-Id` 헤더로 소유자를 식별합니다. **이건 인증이 아닙니다** — 원본의 인증 취약점(서명·만료 없는 이메일 토큰)을 그대로 옮기지 않으려고 일단 헤더로 분리해 뒀고, 인증은 Spring Security 필터로 붙이는 것이 다음 작업입니다.

```bash
curl -X POST localhost:8080/api/coordinations \
  -H 'X-Owner-Id: 1' -H 'Content-Type: application/json' \
  -d '{"title":"출근룩","clothesIds":[1,2]}'
```

중복이면:

```json
{ "error": "duplicate", "retry": true, "clothesIds": [1, 2] }
```

## 설계 메모

**중복 판정을 서버가 한다.** 추천 엔진은 확률적이라 같은 옷장·같은 조건이면 같은 조합을 반복합니다. 프롬프트에 "같은 걸 내지 마라"를 넣는 건 보장이 아니라 요청이라, 무시될 수 있습니다. 그래서 서버가 당일 코디를 **의류 id 집합으로 비교**해 검증하고, 중복이면 409로 거부합니다. 순서만 다른 조합도 같은 것으로 봅니다.

**N:M을 중간 엔티티로.** 옷은 겹쳐 입기 때문에 코디–의류 관계에 **레이어 순서**라는 속성이 붙습니다. `@ManyToMany`로는 표현이 안 돼서 `CoordinationItem`을 명시적으로 두고, `(coordination_id, clothes_id)`에 유니크 제약을 걸었습니다.

**트랜잭션 경계.** 코디 1건과 아이템 N건은 하나의 애그리거트로 묶어 `@Transactional` 안에서 저장합니다. Django 버전에서는 이게 없어서 중간에 실패하면 아이템이 비어 있는 코디가 남을 수 있었습니다. → `PersistenceBehaviorTest`

**N+1을 테스트로 고정.** 목록 조회는 fetch join으로 한 번에 읽습니다. 그런데 "지금 안 나는 것"과 "앞으로도 안 나는 것"은 다르므로, Hibernate 통계로 **실행된 쿼리 수가 정확히 1인지 검증**합니다. 코디 3건·아이템 7건을 읽어도 1회입니다. `open-in-view: false`로 둬서 뷰에서 지연 로딩이 몰래 열리지 않게 했습니다.

**Clock 주입.** "오늘"의 경계가 시스템 시각에 묶여 있으면 자정 근처에서 테스트가 흔들립니다. `Clock`을 빈으로 주입해 고정할 수 있게 뒀습니다.

## 테스트

```
코디 생성 — 중복 판정과 소유권       6개
영속성 계층 — 트랜잭션 경계와 N+1     3개
코디 API — 409 재시도 계약           4개
```

소유권 격리(`남의 옷으로는 코디를 만들 수 없다`)를 테스트로 박아둔 이유는, 원본에서 `user=user` 조건을 뷰 15곳에 손으로 반복하고 있었기 때문입니다. 하나라도 빠지면 IDOR인데 그걸 잡아줄 장치가 없었습니다.

## Django → Spring 대응 메모

이식하면서 확인한 것들입니다.

| Django/DRF | Spring Boot | 원본에서 겪은 것 |
|---|---|---|
| 뷰마다 반복한 인증 4줄 | Security 필터 + `SecurityContextHolder` | 11개 뷰에 복붙돼 있었음 |
| `transaction.atomic()` (누락) | `@Transactional` | 아이템 없는 코디가 남을 수 있었음 |
| `prefetch_related` | fetch join / `@EntityGraph` | 추천 로직엔 걸고 목록 뷰엔 빠뜨림 |
| Serializer (직렬화+검증+저장 겸업) | DTO + `@Valid` + `@Service` | serializer 안에서 외부 API를 호출하고 있었음 |
| 뷰마다 try/except | `@RestControllerAdvice` | 응답 모양이 조금씩 달라져 클라이언트가 키 3개를 fallback |
| 모듈 싱글톤 | DI 빈 | 교체가 안 돼 테스트가 어려웠음 |

## 다음

- [ ] Spring Security + JWT (액세스/리프레시) — 원본의 가장 큰 부채
- [ ] 의류 CRUD 엔드포인트
- [ ] 데스크톱 클라이언트 연결 (로컬 단독 실행용)
