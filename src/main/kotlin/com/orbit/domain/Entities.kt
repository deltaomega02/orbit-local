package com.orbit.domain

import jakarta.persistence.*
import java.time.Instant

enum class MainCategory { TOP, BOTTOM, OUTER }

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
