package com.orbit.domain

import jakarta.persistence.*
import java.time.Instant

enum class MainCategory { TOP, BOTTOM, OUTER }

/**
 * 의류 텍스트 필드의 길이 상한. **컬럼 정의·웹 계층의 `@Size`·서비스의 자르기가
 * 같은 값을 보게 하려고 한 곳에 모은다.**
 *
 * 세 곳에 숫자를 따로 적으면 언젠가 어긋나고, 어긋나는 방향도 나쁘다 — `@Size` 가
 * 컬럼보다 크면 검증을 통과한 값이 INSERT 에서 터지고 사용자에게는 500 으로 보인다.
 * multipart 등록 경로는 `@Valid` 가 걸리지 않아 길이를 손으로 확인하는데, 그 손
 * 검증이 참조할 기준값이 필요한 것도 이유다.
 */
object ClothesLimits {
    const val NAME = 60
    const val COLOR = 30
    const val SUB_CATEGORY = 30
    const val MATERIAL = 30
    const val FIT = 20
    const val SEASON = 20
    const val DETAIL = 200
}

/** 코디 텍스트 필드의 길이 상한. [ClothesLimits] 와 같은 이유로 한 곳에 둔다. */
object CoordinationLimits {
    /**
     * 오늘의 상황 한 줄. 추천을 부를 때마다 프롬프트에 실려 나가므로 길이가 곧
     * 토큰 비용이다. 100자면 "비 오고 쌀쌀한데 면접 보러 가" 같은 문장이 충분히 들어간다.
     */
    const val SITUATION = 100
}

/*
 * [Clothes] · [Coordination] 의 `ownerId` 는 [User] 의 id 다.
 * `@ManyToOne User` 로 연관을 걸지 않고 id 값으로만 참조한다.
 *
 * 이유 두 가지.
 *  1) 소유권 격리에 필요한 것은 id 비교뿐이다. 연관을 걸면 조회마다 User 를 함께
 *     끌고 오거나 프록시를 만들게 되는데, 쓰지도 않을 사용자 정보를 위해 쿼리를
 *     늘릴 이유가 없다. 이 저장소는 N+1 을 테스트로 고정해 두고 있어 특히 그렇다.
 *  2) User 와 Clothes 는 서로 다른 애그리거트다. 다른 애그리거트는 객체 참조가
 *     아니라 id 로 참조하는 편이 경계를 흐리지 않는다.
 *
 * 대가로 DB 레벨 FK 가 없어 고아 행이 생길 수 있다. 지금은 ownerId 가 항상 검증된
 * 토큰의 subject 에서만 오므로 실제로는 생기지 않지만, 사용자 삭제 기능을 붙이는
 * 시점에 정리 로직이 필요하다는 것은 알고 있다.
 */

@Entity
@Table(
    name = "clothes",
    indexes = [
        Index(name = "ix_clothes_owner_category", columnList = "owner_id,main_category"),
        Index(name = "ix_clothes_owner_created", columnList = "owner_id,created_at"),
    ],
)
class Clothes(
    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,

    @Column(nullable = false, length = ClothesLimits.NAME)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "main_category", nullable = false, length = 10)
    var mainCategory: MainCategory,

    @Column(length = ClothesLimits.COLOR)
    var color: String? = null,

    /*
     * ── 아래 네 개는 추천이 실제로 판단에 쓰는 속성축이다 ──────────────────
     *
     * 한동안 사진 분석이 뽑는 것은 이름·카테고리·색·[detail] 한 줄뿐이었다. 그런데
     * "얇은 리넨 셔츠"와 "두꺼운 기모 니트"는 둘 다 `TOP / 화이트` 다 — 추천이
     * 계절감을 맞추려 해도 맞출 근거가 없었고, detail 한 줄에 소재·핏·계절이 뭉쳐
     * 있으면 모델이 그걸 다시 풀어 읽어야 한다. 축을 나눠 두면 프롬프트에서
     * "계절이 어긋나는 조합은 피한다" 같은 규칙이 가리킬 대상이 생긴다.
     *
     * 전부 nullable 이고 **비어 있는 것이 정상이다.** 컬럼이 추가되기 전에 등록된
     * 옷은 전부 null 이고, 사용자가 채우지 않을 수도 있다. 추천 프롬프트는 값이
     * 없는 속성을 아예 생략한다(있는 척하면 모델이 지어낸다).
     *
     * 자유 문자열로 두고 enum 을 쓰지 않았다. 옷의 소재와 핏은 계속 새 이름이
     * 생기는 영역이라, 서버가 목록을 고정하면 사용자가 자기 옷을 자기 말로 적을 수
     * 없게 된다. [season] 만은 AI 응답 단계에서 네 값으로 좁히는데, 그건 **모델이
     * 답하는 방식**의 제약이지 사용자 입력의 제약이 아니다
     * ([com.orbit.ai.gemini.GeminiClothingAnalyzer.SEASONS]).
     */

    /** 세부 종류. 셔츠·니트·맨투맨·청바지·슬랙스·코트 … [mainCategory] 를 한 단계 좁힌다. */
    @Column(name = "sub_category", length = ClothesLimits.SUB_CATEGORY)
    var subCategory: String? = null,

    /** 소재. 면·울 혼방·데님·리넨·기모 … 계절감 판단의 1차 근거다. */
    @Column(length = ClothesLimits.MATERIAL)
    var material: String? = null,

    /** 핏. 오버핏·슬림·와이드·레귤러 … 상하의 실루엣 균형을 맞추는 데 쓴다. */
    @Column(length = ClothesLimits.FIT)
    var fit: String? = null,

    /** 어울리는 계절. 봄·가을 / 여름 / 겨울 / 사계절. */
    @Column(length = ClothesLimits.SEASON)
    var season: String? = null,

    /**
     * 위 축으로 나누어지지 않는 한 줄 요약. 사진 분석이 채우고 사용자가 고칠 수 있다.
     *
     * 속성축이 생긴 뒤에도 남겨 둔다. "밑단에 자수가 있다"거나 "단추가 나무"처럼
     * 축에 담기지 않는 관찰이 실제로 추천 이유가 되기 때문이고, 이미 저장된 옷들이
     * 이 필드에만 정보를 갖고 있기 때문이기도 하다.
     */
    @Column(length = ClothesLimits.DETAIL)
    var detail: String? = null,

    /**
     * 미디어 루트 기준 상대 경로(예: `clothes/2026/08/12/{uuid}.jpg`).
     *
     * URL 이 아니라 경로를 저장한다. URL 을 그대로 넣으면 호스트·포트·라우팅이
     * 바뀌는 순간 과거 행이 전부 깨진 링크가 된다. 응답에 쓸 URL 은 웹 계층이
     * 이 값 앞에 `/media/` 를 붙여 그때그때 만든다.
     */
    @Column(name = "image_path", length = 200)
    var imagePath: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    /**
     * 옷장에서 치운 시각. null 이면 현역이다.
     *
     * **왜 소프트 삭제인가.** 이 앱은 "이 옷으로 뭘 입었었지"를 보는 기록 앱이다.
     * 그런데 코디에 한 번이라도 쓰인 옷은 물리 삭제할 수 없다 — 지우는 순간 과거
     * 코디의 아이템이 사라져 기록이 깨진다. 그렇다고 삭제를 영구히 막으면
     * (이전 구현의 409) 버린 옷이 옷장에 영원히 남고 추천 후보에도 계속 올라온다.
     *
     * 두 요구는 사실 충돌하지 않는다. 서로 다른 것을 원하기 때문이다 —
     * 옷장은 "지금 입을 수 있는 옷"을, 기록은 "그때 입은 옷"을 원한다.
     * 행을 남긴 채 숨기면 둘 다 만족한다. 그래서 삭제는
     *  - 코디에 쓰인 적이 없으면 물리 삭제(행 + 이미지 파일까지 정리)
     *  - 쓰인 적이 있으면 이 컬럼을 채워 옷장·통계·추천 후보에서만 제외
     * 로 갈린다. 사용자가 보는 결과는 양쪽 다 "옷장에서 사라졌다"로 같다.
     *
     * 대가는 두 가지다. (1) 옷장 조회 쿼리마다 `deletedAt is null` 조건이 붙고,
     * 빠뜨리면 지운 옷이 되살아난다 — 그래서 조건을 리포지토리 메서드 이름에
     * 박아 두고 서비스가 임의로 조회하지 못하게 했다. (2) 소프트 삭제된 옷의
     * 이미지 파일은 남는다. 과거 코디 화면이 그 사진을 그대로 보여주므로
     * 이건 고아 파일이 아니라 여전히 참조되는 파일이다.
     */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
}

/**
 * 코디. 아이템은 코디의 생명주기에 종속되므로 cascade + orphanRemoval 로 묶는다.
 * Django 버전에서는 코디와 아이템을 트랜잭션 없이 따로 저장해, 중간 실패 시
 * 아이템이 비어 있는 코디가 남을 수 있었다. 여기서는 하나의 애그리거트로 다룬다.
 */
@Entity
@Table(
    name = "coordination",
    indexes = [Index(name = "ix_coordination_owner_created", columnList = "owner_id,created_at")],
    /*
     * 사용자별 LOOK 번호는 **DB 가 지키는 불변식**이다.
     *
     * 번호 부여는 "지금까지 발급한 최대값 + 1" 이라, 읽고 쓰는 사이에 다른 요청이
     * 끼면 같은 번호가 두 번 나온다. 추천이 중복 조합으로 409 를 받고 곧바로
     * 재시도하는 흐름이 실제로 있어서, 짧은 간격의 연속 생성은 가정이 아니라
     * 정상 동작이다. "단일 사용자 로컬 앱이라 아마 안 겹칠 것"에 기대면 겹쳤을 때
     * 아무도 모르고 지나가고, 화면에는 LOOK 007 이 두 개 뜬다.
     *
     * 그래서 겹침을 막는 책임을 애플리케이션이 아니라 제약에 둔다. 애플리케이션
     * 로직(락·재시도)은 "겹치지 않게 하려는 노력"이고, 이 제약은 "겹치면 반드시
     * 실패한다"는 보장이다. 앞의 것이 언젠가 틀려도 뒤의 것은 틀리지 않는다.
     *
     * `look_no` 가 nullable 인 것과 충돌하지 않는다 — H2·MySQL 모두 유니크 제약에서
     * NULL 은 서로 다른 값으로 보므로, 마이그레이션 전의 기존 행이 여럿 NULL 이어도
     * 제약을 걸 수 있다. (자세한 사정은 아래 lookNo 주석)
     */
    uniqueConstraints = [
        UniqueConstraint(name = "uq_coordination_owner_look_no", columnNames = ["owner_id", "look_no"]),
    ],
)
class Coordination(
    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,

    @Column(nullable = false, length = 80)
    var title: String,

    /**
     * 추천 이유. AI 추천에만 값이 있고 수동 생성은 null 이다.
     * "왜 이 조합인지"가 이 앱의 사용 이유라 코디와 같은 행에 붙여 둔다.
     */
    @Column(length = 500)
    var reason: String? = null,

    /**
     * 사용자가 추천을 요청하며 적어 준 오늘의 상황("비 오고 쌀쌀해", "면접 보러 가").
     * 적지 않고 추천받았거나 수동 생성이면 null 이다.
     *
     * **왜 저장하는가.** 이 앱은 "그때 왜 이걸 입었지"를 다시 보는 기록 앱이다.
     * 상황은 그 질문에 대한 답의 절반이다 — [reason] 이 "왜 이 조합인가"를 말한다면
     * 이 값은 "무엇을 위한 조합이었나"를 말한다. 프롬프트에만 쓰고 버리면 같은
     * 코디가 목록에서 이유 없는 조합으로 남는다.
     *
     * 사용자가 자기 말로 적은 문장이라 그대로 화면에 나간다. 프롬프트에 넣을 때의
     * 취급(지시문이 아니라 맥락)은
     * [com.orbit.ai.gemini.GeminiOutfitRecommender.buildPrompt] 에 적어 뒀다.
     */
    @Column(length = CoordinationLimits.SITUATION)
    var situation: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    /**
     * 화면이 보여주는 `LOOK 014` 의 그 번호. **사용자별 순번이고, 생성 시점에 정해져
     * 이후 절대 바뀌지 않는다.**
     *
     * 왜 저장하는가. 이전에는 화면이 DB 의 id 를 세 자리로 채워서 만들고 있었는데
     * 두 가지가 틀렸다. (1) id 는 전역 시퀀스라 사용자가 둘만 돼도 내 첫 코디가
     * LOOK 037 로 시작한다. (2) 계산해서 만드는 번호(예: 목록에서의 순서)는 앞의
     * 코디를 지우는 순간 뒤의 번호가 통째로 밀린다 — 어제 본 LOOK 005 가 오늘
     * LOOK 004 가 되는 기록 앱은 기록 앱이 아니다. 저장된 값이어야 안 밀린다.
     *
     * **구멍(004 / 003 / 001)은 그대로 둔다.** 지운 번호를 다시 쓰면 "LOOK 002" 가
     * 가리키는 대상이 바뀐다. 사용자가 스크린샷을 남기거나 "2번 코디 좋았는데"라고
     * 기억하는 곳에서, 번호가 조용히 다른 코디를 가리키는 것은 번호가 하나 비는
     * 것보다 훨씬 나쁘다. QA 가 지적한 "002 어디 갔지?" 는 사실 정확한 독해다 —
     * 002 는 있었고 사용자가 지웠다. 빈 번호는 삭제의 흔적이지 결함이 아니다.
     * (그래서 [LookCounter] 가 발급 이력을 따로 기억한다. 남아 있는 코디의
     *  최대값 + 1 로 계산하면 마지막 코디를 지웠을 때 그 번호가 재사용된다.)
     *
     * 왜 nullable 인가. 이미 데이터가 쌓인 상태에서 컬럼이 추가되므로 기존 행에는
     * 값이 없다. `not null` 로 만들면 그 ALTER 자체가 실패한다(기본값을 줘도 —
     * 모든 기존 행이 같은 값이 되어 위 유니크 제약을 위반한다). 그래서 컬럼은
     * nullable 로 두고, 값은 [com.orbit.service.LookNumberBackfill] 이 기동 시
     * 생성 시각 순서대로 채운다. 새로 만들어지는 코디는 언제나 값을 갖는다 —
     * 부여 지점이 [com.orbit.service.CoordinationCreator.createOnce] 하나뿐이다.
     */
    @Column(name = "look_no")
    var lookNo: Int? = null

    /**
     * 가상 착용 이미지 경로. 한 번 생성되면 다시 만들지 않는다(멱등).
     * 이미지 생성은 느리고 호출당 비용이 붙는 자원이라, 같은 코디를 여러 번
     * 눌렀다고 매번 새로 만들면 그대로 요금과 대기 시간이 된다.
     */
    @Column(name = "try_on_image_path", length = 200)
    var tryOnImagePath: String? = null

    /**
     * 즐겨찾기. 별도 테이블로 빼지 않은 이유는 이 앱에 공유가 없어 코디 하나당
     * 값이 하나뿐이기 때문이다. 조인을 하나 더 만들면 목록 조회의 쿼리 수만 늘고,
     * 이 저장소는 목록의 쿼리 수를 테스트로 고정하고 있다.
     */
    @Column(nullable = false)
    var favorite: Boolean = false

    @OneToMany(
        mappedBy = "coordination",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @OrderBy("layerOrder ASC")
    private val mutableItems: MutableList<CoordinationItem> = mutableListOf()

    val items: List<CoordinationItem> get() = mutableItems

    fun addItem(clothes: Clothes, layerOrder: Int) {
        mutableItems += CoordinationItem(this, clothes, layerOrder)
    }

    /** 이 코디가 담고 있는 의류 id 집합. 중복 판정의 기준값이다. */
    fun clothesIdSet(): Set<Long> = mutableItems.mapNotNull { it.clothes.id }.toSet()
}

/**
 * 사용자별 LOOK 번호 발급 이력. 행 하나에 사용자 하나.
 *
 * **왜 테이블을 하나 더 두는가.** 다음 번호를 `max(look_no) + 1` 로 계산하면 마지막
 * 코디를 지웠을 때 그 번호가 곧바로 재사용된다 — LOOK 003 을 지우고 새로 만들면
 * 새 코디가 다시 LOOK 003 이 된다. 코디는 물리 삭제되므로 지워진 번호는 코디
 * 테이블에 흔적이 남지 않고, 그래서 "지금까지 몇 번까지 나갔는가"는 따로 기억하는
 * 수밖에 없다. 이 행이 그 기억이다. 값은 단조 증가만 하고 삭제로 줄지 않는다.
 *
 * **왜 [User] 에 컬럼으로 붙이지 않았는가.** 이 저장소는 의도적으로 코디·의류에서
 * User 로 FK 를 걸지 않고 id 값으로만 참조한다(파일 위쪽 주석). 번호 발급을 User
 * 행에 의존시키면 "코디를 만들려면 users 행이 있어야 한다"는 결합이 새로 생긴다.
 * 소유자 id 를 키로 하는 별도 행이면 지금의 참조 규칙을 그대로 따른다.
 *
 * PK 가 소유자 id 다 — 사용자당 한 행뿐이라 대리 키를 둘 이유가 없고, PK 자체가
 * "사용자당 하나"를 강제한다.
 */
@Entity
@Table(name = "look_counter")
class LookCounter(
    @Id
    @Column(name = "owner_id")
    val ownerId: Long,

    /** 이 사용자에게 마지막으로 발급한 번호. 0 이면 아직 하나도 안 나갔다. */
    @Column(name = "last_look_no", nullable = false)
    var lastLookNo: Int = 0,
)

/**
 * 코디-의류 N:M 중간 엔티티.
 *
 * 단순 @ManyToMany 를 쓰지 않은 이유는 관계 자체에 속성(레이어 순서)이 붙기 때문이다.
 * 옷은 겹쳐 입으므로 "이 코디에 포함된다"만으로는 표현이 부족하다.
 */
@Entity
@Table(
    name = "coordination_item",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_coordination_clothes", columnNames = ["coordination_id", "clothes_id"]),
    ],
)
class CoordinationItem(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coordination_id", nullable = false)
    val coordination: Coordination,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clothes_id", nullable = false)
    val clothes: Clothes,

    @Column(name = "layer_order", nullable = false)
    val layerOrder: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
