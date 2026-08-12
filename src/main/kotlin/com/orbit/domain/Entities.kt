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
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
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
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

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
