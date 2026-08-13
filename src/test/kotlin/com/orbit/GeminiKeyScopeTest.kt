package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * **Gemini 키는 계정이 아니라 기기에 묶인다.** 이 테스트는 그 사실을 고정한다.
 *
 * 감사에서 "새 계정이 기존 계정의 키를 그대로 물려받는다"가 지적됐고, 확인 결과
 * 의도된 동작이 맞다고 판단했다. 근거는 [com.orbit.ai.gemini.GeminiKeyStore] 주석과
 * README 에 적어 뒀다 — 요약하면 (1) 키가 실제로 묶이는 대상은 앱 계정이 아니라 OS
 * 계정이고 (2) 계정별로 나누려면 키를 DB 로 옮겨야 해서 보관 위치가 오히려 나빠지며
 * (3) 이 앱의 계정은 보안 경계가 아니라 옷장의 소유자이기 때문이다.
 *
 * **문서만으로는 지켜지지 않으므로 테스트로 박아 둔다.** 누군가 나중에 키를 계정별로
 * 바꾸면 이 테스트가 깨지고, 그때 위 판단을 다시 읽게 된다. 반대로 조용히 바뀌어
 * 화면 문구("이 기기의 Orbit 전체에 적용돼요")만 거짓이 되는 상황은 막는다.
 *
 * 값을 저장하지 않고 **읽기만** 한다. `PUT` 은 사용자 데이터 폴더의 실제 `gemini.key`
 * 를 건드리므로, 테스트가 개발자의 진짜 키를 덮어쓰는 일이 없어야 한다. 같은 이유로
 * "키가 없다"를 단언하지 않는다 — 그건 이 기계의 상태이지 계약이 아니다.
 * 계약은 **"계정이 달라도 같은 것이 보인다"** 쪽이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Gemini 키 — 계정이 아니라 기기 단위")
class GeminiKeyScopeTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var api: TestApiClient

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
    }

    private fun statusFor(session: Session): String = mockMvc.perform(
        get("/api/settings/gemini-key").header(HttpHeaders.AUTHORIZATION, session.bearer),
    ).andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `키 상태는 계정이 달라도 같다 — 앱 전역 설정이다`() {
        val first = api.signUpAndLogin("key-first@orbit.test")
        val second = api.signUpAndLogin("key-second@orbit.test")

        assertEquals(
            statusFor(first),
            statusFor(second),
            "키는 계정별이 아니라 이 기기의 Orbit 전체에 적용된다. " +
                "이 단언이 깨졌다면 키 범위가 바뀐 것이고, 화면 문구와 README 도 함께 고쳐야 한다.",
        )
    }

    @Test
    fun `키 설정은 인증 뒤에 있다`() {
        // 로컬 앱이라도 열어 두면 같은 네트워크의 아무나 자기 키를 심거나 남의 키를 지울 수 있다.
        mockMvc.perform(get("/api/settings/gemini-key")).andExpect(status().isUnauthorized)
    }
}
