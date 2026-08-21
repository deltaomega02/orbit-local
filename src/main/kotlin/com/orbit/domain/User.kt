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
     * 이 계정이 "이 앱의 주인"인가([com.orbit.service.OwnerAccountService]).
     *
     * 로그인 화면이 없어진 뒤, 서버는 자격증명 없이 누구의 토큰을 내줄지 스스로
     * 정해야 한다. 그 판단은 기존 데이터를 보고 **한 번만** 내리고 결과를 여기에
     * 적어 둔다. 매 기동마다 다시 계산하면 데이터가 움직일 때 답이 바뀔 수 있고,
     * 주인이 바뀐다는 것은 사용자에게 "옷장이 통째로 바뀐다"로 보인다.
     *
     * **타입이 `Boolean?` 인 것은 의도적이다.** 스키마는 `ddl-auto: update` 로
     * 따라오는데, 그 방식은 이미 행이 들어 있는 테이블에 컬럼을 추가할 때 값을
     * 채워 주지 않는다. 기존 행은 NULL 이 되므로 non-null `Boolean` 으로 받으면
     * 기존 사용자를 읽는 순간 터진다. NULL 은 "아직 판단하지 않았다"로 읽는다.
     */
    @Column(name = "is_owner")
    var ownerFlag: Boolean? = null

    /**
     * 화면 이름을 **사용자가 직접 바꿨는가**([com.orbit.service.UserService.updateDisplayName]).
     *
     * 이 값이 true 면 [com.orbit.service.OwnerAccountService] 가 기동할 때마다 하는
     * 설정 동기화를 건너뛴다. 사용자가 앱에서 고른 이름이 설정 파일보다 우선한다 —
     * 화면에서 바꿀 수 있게 해 놓고 다음 기동에 되돌려 버리면 바꾼 적이 없는 것과 같다.
     *
     * `ownerFlag` 와 같은 이유로 타입이 `Boolean?` 이다. `ddl-auto: update` 는 기존
     * 행에 값을 채워 주지 않으므로 NULL 이 들어온다. NULL 은 "직접 바꾼 적 없음"으로
     * 읽어 종전대로 설정을 따른다.
     */
    @Column(name = "display_name_customized")
    var displayNameCustomized: Boolean? = null

    /**
     * 로그를 찍다 실수로 해시가 새는 일을 막는다. 해시는 평문보다 안전하지만
     * 유출되면 오프라인 대입 공격의 재료가 되므로 출력 대상이 아니다.
     */
    override fun toString(): String = "User(id=$id, email=$email)"
}
