package com.orbit.domain

import jakarta.persistence.*
import java.time.Instant

enum class MainCategory { TOP, BOTTOM, OUTER }

/** 컬럼 길이·`@Size`·multipart 수동 검증·서비스 자르기가 함께 쓰는 길이 상한. */
object ClothesLimits {
    const val NAME = 60
    const val COLOR = 30
    const val SUB_CATEGORY = 30
    const val MATERIAL = 30
    const val FIT = 20
    const val SEASON = 20
    const val DETAIL = 200
}

object CoordinationLimits {
    /** 오늘의 상황 한 줄. 매번 프롬프트에 실리므로 길이가 곧 토큰 비용이다. */
    const val SITUATION = 100
}

// ownerId 는 User 연관 없이 id 값으로만 참조한다(다른 애그리거트, 불필요한 조회 방지).
// TODO: FK 가 없으므로 사용자 삭제 기능을 넣을 때 소유 데이터 정리 로직이 필요하다

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

    // 추천이 판단에 쓰는 속성. 전부 nullable 이고 비어 있는 것이 정상이다.
    // 소재·핏은 계속 새 이름이 생기므로 enum 이 아닌 자유 문자열로 둔다.

    /** 셔츠·니트·청바지·코트 등 [mainCategory] 의 하위 종류. */
    @Column(name = "sub_category", length = ClothesLimits.SUB_CATEGORY)
    var subCategory: String? = null,

    @Column(length = ClothesLimits.MATERIAL)
    var material: String? = null,

    @Column(length = ClothesLimits.FIT)
    var fit: String? = null,

    /** 표준 표기는 [Seasons]. 추천 규칙이 직접 비교하므로 아는 옛 표기는 저장 전에 정규화한다. */
    @Column(length = ClothesLimits.SEASON)
    var season: String? = null,

    /** 속성으로 나뉘지 않는 한 줄 요약(예: 밑단 자수). */
    @Column(length = ClothesLimits.DETAIL)
    var detail: String? = null,

    /** 미디어 루트 기준 상대 경로(예: `clothes/2026/08/12/{uuid}.jpg`). URL 은 웹 계층에서 만든다. */
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
     * 소프트 삭제 시각. 코디에 쓰인 옷은 기록 보존을 위해 행과 이미지를 남기고 옷장·통계·추천에서만 뺀다.
     * 쓰인 적 없는 옷은 물리 삭제한다. 옷장 조회는 반드시 `deletedAt is null` 조건을 붙인다.
     */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
}

/** 아이템은 코디 생명주기에 종속되므로 cascade + orphanRemoval 로 묶는다. */
@Entity
@Table(
    name = "coordination",
    indexes = [Index(name = "ix_coordination_owner_created", columnList = "owner_id,created_at")],
    // 연속 생성 시 LOOK 번호가 겹치지 않도록 DB 제약으로 보장한다.
    // H2·MySQL 은 유니크 제약에서 NULL 끼리 다른 값으로 보므로 look_no 가 nullable 이어도 된다.
    uniqueConstraints = [
        UniqueConstraint(name = "uq_coordination_owner_look_no", columnNames = ["owner_id", "look_no"]),
    ],
)
class Coordination(
    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,

    @Column(nullable = false, length = 80)
    var title: String,

    /** AI 추천 이유. 수동 생성은 null. */
    @Column(length = 500)
    var reason: String? = null,

    /** 추천 요청 시 사용자가 적은 오늘의 상황. 없거나 수동 생성이면 null. */
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
     * 화면의 `LOOK 014` 번호. 사용자별 순번이며 생성 후 바뀌지 않고, 지운 번호는 재사용하지 않는다.
     * 기존 행 때문에 nullable 이며 [com.orbit.service.LookNumberBackfill] 이 기동 시 채운다.
     * 새 코디의 번호는 [com.orbit.service.CoordinationCreator.createOnce] 에서만 부여한다.
     */
    @Column(name = "look_no")
    var lookNo: Int? = null

    /** 가상 착용 이미지 경로. 생성 비용이 크므로 이미 있으면 다시 만들지 않는다. */
    @Column(name = "try_on_image_path", length = 200)
    var tryOnImagePath: String? = null

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

    /** 중복 조합 판정 기준. */
    fun clothesIdSet(): Set<Long> = mutableItems.mapNotNull { it.clothes.id }.toSet()
}

/**
 * 사용자별 마지막 발급 LOOK 번호. `max(look_no) + 1` 로 계산하면 마지막 코디를 지웠을 때 번호가 재사용된다.
 * 값은 증가만 하며, User 에 의존하지 않도록 소유자 id 를 PK 로 하는 별도 테이블에 둔다.
 */
@Entity
@Table(name = "look_counter")
class LookCounter(
    @Id
    @Column(name = "owner_id")
    val ownerId: Long,

    /** 0 이면 아직 발급 전. */
    @Column(name = "last_look_no", nullable = false)
    var lastLookNo: Int = 0,
)

/** 코디-의류 중간 엔티티. 관계에 레이어 순서가 붙으므로 @ManyToMany 대신 쓴다. */
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
