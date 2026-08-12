package com.orbit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 인증 주체.
 *
 * `password` 가 아니라 `passwordHash` 라는 이름을 쓴 이유는, 이 필드에 평문이
 * 들어가는 코드가 리뷰에서 눈에 띄게 하기 위해서다. 해싱은 [com.orbit.service.AuthService]
 * 한 곳에서만 일어난다.
 *
 * `user` 는 H2/MySQL 모두에서 예약어라 테이블명은 `users` 로 둔다.
 */
@Entity
@Table(
    name = "users",
    uniqueConstraints = [UniqueConstraint(name = "uq_users_email", columnNames = ["email"])],
)
class User(
    @Column(nullable = false, length = 190)
    val email: String,

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String,

    @Column(name = "display_name", nullable = false, length = 40)
    var displayName: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    /**
     * 로그를 찍다 실수로 해시가 새는 일을 막는다. 해시는 평문보다 안전하지만
     * 유출되면 오프라인 대입 공격의 재료가 되므로 출력 대상이 아니다.
     */
    override fun toString(): String = "User(id=$id, email=$email)"
}
