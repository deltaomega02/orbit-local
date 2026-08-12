package com.orbit.domain

import jakarta.persistence.*
import java.time.Instant

enum class MainCategory { TOP, BOTTOM, OUTER }

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

    @Column(nullable = false, length = 60)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "main_category", nullable = false, length = 10)
    var mainCategory: MainCategory,

    @Column(length = 30)
    var color: String? = null,

    /**
     * 소재·핏·어울리는 상황. 사진 분석으로 자동으로 채워지고 사용자가 고칠 수 있다.
     * 추천 프롬프트에 그대로 들어간다 — 이름과 색만으로는 "얇은 리넨"과 "두꺼운 니트"를
     * 구분할 수 없어서, 계절이나 상황에 맞는 조합이 나오지 않는다.
     */
    @Column(length = 200)
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
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

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
