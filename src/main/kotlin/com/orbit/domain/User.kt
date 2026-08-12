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

    /** 가상 착용의 바탕이 되는 전신 사진. 미디어 루트 기준 상대 경로. */
    @Column(name = "body_photo_path", length = 200)
    var bodyPhotoPath: String? = null,

    /**
     * 추천에 반영할 취향 한 문장(예: "카고팬츠 자주 넣어줘").
     *
     * 구조화된 필드(선호 카테고리·색 목록)로 받지 않은 이유: 취향은 열거로 담기지
     * 않는다. "비 오는 날엔 밝은 색 피해줘" 같은 조건까지 스키마로 만들면 필드가
     * 끝없이 늘어나고, 정작 사용자는 자기 말로 쓰지 못한다. 어차피 이 값을 읽는
     * 쪽이 자연어를 이해하는 모델이므로 자연어 그대로 넘기는 편이 손실이 없다.
     *
     * 대가는 프롬프트 인젝션 표면이다. 사용자가 자기 계정의 추천 프롬프트에
     * 임의 문자열을 넣을 수 있게 된다. 다만 (1) 남의 데이터에는 닿지 않고
     * (2) 추천 결과의 id 는 서버가 소유 목록과 대조해 다시 검증하므로
     * ([com.orbit.service.OutfitAiService.recommend]) 최악의 결과는 "이상한 코디가
     * 하나 나온다"에 그친다. 길이는 200자로 자른다.
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
     * 로그를 찍다 실수로 해시가 새는 일을 막는다. 해시는 평문보다 안전하지만
     * 유출되면 오프라인 대입 공격의 재료가 되므로 출력 대상이 아니다.
     */
    override fun toString(): String = "User(id=$id, email=$email)"
}
