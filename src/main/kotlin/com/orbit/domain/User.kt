package com.orbit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

// user 는 H2/MySQL 예약어라 테이블명은 users. 해싱은 AuthService 에서만 한다.
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

    /** 가상 착용에 쓰는 전신 사진. 미디어 루트 기준 상대 경로. */
    @Column(name = "body_photo_path", length = 200)
    var bodyPhotoPath: String? = null,

    /**
     * 추천 프롬프트에 그대로 넣는 자연어 취향 한 문장(예: "카고팬츠 자주 넣어줘").
     * 프롬프트 인젝션이 가능하지만 추천 결과 id 는 서버가 소유 목록과 다시 대조한다.
     */
    @Column(name = "style_preference", length = 200)
    var stylePreference: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    /**
     * 앱 주인 계정 여부. 주인이 바뀌지 않도록 한 번 판단한 결과를 저장한다([com.orbit.service.OwnerAccountService]).
     * ddl-auto: update 는 기존 행에 값을 채우지 않으므로 nullable 이며, NULL 은 아직 판단 전.
     */
    @Column(name = "is_owner")
    var ownerFlag: Boolean? = null

    /**
     * true 면 기동 시 설정 파일의 이름으로 덮어쓰지 않는다.
     * ownerFlag 와 같은 이유로 nullable 이며, NULL 은 직접 바꾼 적 없음.
     */
    @Column(name = "display_name_customized")
    var displayNameCustomized: Boolean? = null

    // 로그에 비밀번호 해시가 찍히지 않도록 한다
    override fun toString(): String = "User(id=$id, email=$email)"
}
