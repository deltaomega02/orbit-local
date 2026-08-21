package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.domain.User
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import com.orbit.service.OwnerAccountService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 화면에 보이는 이름을 앱 안에서 바꾼다.
 *
 * 이 기능에서 정작 어려운 것은 저장이 아니라 **저장한 것이 남아 있는가**다.
 * [OwnerAccountService] 는 세션을 열 때마다 설정값으로 이름을 맞춰 왔다.
 * 그 동기화를 그대로 두면 사용자가 바꾼 이름이 다음 기동에 되돌아가고,
 * 사용자에게는 "바꿨는데 안 바뀌었다"로 보인다 — 바꾸는 화면이 없는 것보다 나쁘다.
 *
 * 그래서 여기서 지키는 것은 셋이다.
 *  1. 바꾼 이름이 저장되고 응답과 조회에 반영된다
 *  2. **바꾼 뒤에는 설정 동기화가 그 이름을 건드리지 않는다**
 *  3. 한 번도 바꾸지 않은 계정은 종전대로 설정을 따른다 (배포본 기본 이름이 유지된다)
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("표시 이름 — 앱에서 바꾸고, 바꾼 것이 남는다")
class DisplayNameTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var ownerAccountService: OwnerAccountService
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private lateinit var api: TestApiClient

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
    }

    private fun openSession(): String {
        val body = mockMvc.perform(post("/api/auth/session"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return "Bearer " + (api.json(body)["accessToken"] as String)
    }

    private fun putName(bearer: String, name: String) =
        mockMvc.perform(
            put("/api/users/me/display-name")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("displayName" to name))),
        )

    // ── 저장 ──────────────────────────────────────────────────────

    @Test
    fun `바꾼 이름이 응답과 조회에 함께 반영된다`() {
        val bearer = openSession()

        putName(bearer, "あたらしい名前")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("あたらしい名前"))

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("あたらしい名前"))
    }

    @Test
    fun `앞뒤 공백은 저장 전에 떨어진다`() {
        val bearer = openSession()

        putName(bearer, "  あたらしい名前  ")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("あたらしい名前"))
    }

    @Test
    fun `40자를 넘기면 잘라서 저장한다`() {
        val bearer = openSession()
        val long = "あ".repeat(60)

        putName(bearer, long)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("あ".repeat(40)))
    }

    @Test
    fun `빈 이름은 400 으로 거절한다`() {
        val bearer = openSession()

        // 이름은 취향과 달리 비울 수 있는 값이 아니다. 비면 사람을 부를 말이 사라진다.
        putName(bearer, "   ").andExpect(status().isBadRequest)
        putName(bearer, "").andExpect(status().isBadRequest)
    }

    @Test
    fun `토큰 없이 바꿀 수 없다`() {
        mockMvc.perform(
            put("/api/users/me/display-name")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("displayName" to "누구든"))),
        ).andExpect(status().isUnauthorized)
    }

    // ── 되돌아가지 않는가 (이 기능의 핵심) ──────────────────────────

    @Test
    fun `바꾼 이름은 다음 세션에서 설정값으로 되돌아가지 않는다`() {
        val bearer = openSession()
        putName(bearer, "あたらしい名前").andExpect(status().isOk)

        // 세션을 다시 여는 것은 앱을 다시 켜는 것과 같다. 여기서 설정 동기화가 돈다.
        val again = openSession()

        mockMvc.perform(get("/api/users/me").header("Authorization", again))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("あたらしい名前"))
    }

    @Test
    fun `resolveOrCreate 를 여러 번 불러도 바꾼 이름이 남는다`() {
        val bearer = openSession()
        putName(bearer, "あたらしい名前").andExpect(status().isOk)

        repeat(3) { ownerAccountService.resolveOrCreate() }

        val owner = requireNotNull(userRepository.findAll().firstOrNull { it.ownerFlag == true })
        assertEquals("あたらしい名前", owner.displayName)
        assertTrue(owner.displayNameCustomized == true, "바꿨다는 표시가 남아 있어야 한다")
    }

    @Test
    fun `한 번도 바꾸지 않았으면 설정값을 따른다`() {
        // 배포본의 기본 이름을 설정 한 줄로 정하는 방식은 그대로 살아 있어야 한다.
        // 이어받을 계정의 이름을 일부러 다르게 두고, 동기화가 설정값으로 맞추는지 본다.
        val stale = userRepository.saveAndFlush(
            User(
                email = "existing@orbit.test",
                passwordHash = passwordEncoder.encode("orbit-test-1234"),
                displayName = "옛 이름",
            ),
        )
        assertEquals("옛 이름", stale.displayName)

        val owner = ownerAccountService.resolveOrCreate()

        assertEquals(requireNotNull(stale.id), requireNotNull(owner.id), "옷장을 가진 계정을 이어받아야 한다")
        // 테스트 프로파일의 orbit.owner.display-name 값이다. 코드 기본값(ユーザー)과
        // 일부러 다르게 두어, 설정을 실제로 읽었는지 우연히 맞은 것인지를 구별한다.
        assertEquals("テスト主", owner.displayName, "바꾼 적이 없으면 설정값을 따른다")
    }

    @Test
    fun `바꾼 뒤에는 이어받기 동기화가 이름을 건드리지 않는다`() {
        val existing = userRepository.saveAndFlush(
            User(
                email = "existing@orbit.test",
                passwordHash = passwordEncoder.encode("orbit-test-1234"),
                displayName = "옛 이름",
            ),
        )
        // 사용자가 직접 골랐다는 표시를 세워 둔다 (앱에서 저장한 것과 같은 상태).
        existing.displayName = "あたらしい名前"
        existing.displayNameCustomized = true
        userRepository.saveAndFlush(existing)

        val owner = ownerAccountService.resolveOrCreate()

        assertEquals("あたらしい名前", owner.displayName, "설정값으로 덮어쓰면 안 된다")
    }
}
