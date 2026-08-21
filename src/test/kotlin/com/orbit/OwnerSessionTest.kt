package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 자동 세션과 계정 이어받기.
 *
 * 여기서 지켜야 하는 것은 두 가지고, 두 번째가 훨씬 무겁다.
 *  1. 로그인 없이 토큰이 나오고, 그 토큰으로 기존 API 가 그대로 동작한다.
 *  2. **이미 데이터가 있는 DB 에서 기동하면 그 옷장이 그대로 보인다.**
 *     여기서 틀리면 사용자는 앱을 켜자마자 빈 옷장을 본다. 데이터는 DB 에 남아
 *     있지만, 화면에서 사라진 것과 "잃었다"는 사용자에게 같은 말이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("자동 세션 — 로그인 없이 주인의 옷장을 연다")
class OwnerSessionTest {

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

    /** 이미 쓰던 계정 하나를 만든다. 이메일은 설정값(owner@orbit.test)과 일부러 다르다. */
    private fun existingUser(email: String, displayName: String = "옛 이름"): User =
        userRepository.saveAndFlush(
            User(
                email = email,
                passwordHash = passwordEncoder.encode("orbit-test-1234"),
                displayName = displayName,
            ),
        )

    private fun clothesOf(ownerId: Long, name: String, category: MainCategory) =
        clothesRepository.saveAndFlush(Clothes(ownerId = ownerId, name = name, mainCategory = category))

    private fun openSession(): Session {
        val body = mockMvc.perform(post("/api/auth/session"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val parsed = api.json(body)
        return Session(
            userId = 0L,
            email = "",
            accessToken = parsed["accessToken"] as String,
            refreshToken = parsed["refreshToken"] as String,
        )
    }

    // ── 발급 ──────────────────────────────────────────────────────

    @Test
    fun `본문도 자격증명도 없이 토큰 한 쌍을 받는다`() {
        mockMvc.perform(post("/api/auth/session"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
    }

    @Test
    fun `받은 토큰으로 보호된 API 가 그대로 동작한다`() {
        val session = openSession()

        mockMvc.perform(get("/api/clothes").header("Authorization", session.bearer))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/coordinations/today").header("Authorization", session.bearer))
            .andExpect(status().isOk)
    }

    @Test
    fun `토큰 없이 부르면 여전히 401 이다`() {
        // 자동 세션은 "토큰을 쉽게 얻는 길"이지 "토큰이 필요 없어졌다"가 아니다.
        mockMvc.perform(get("/api/clothes")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `여러 번 불러도 계정은 하나뿐이다`() {
        openSession()
        openSession()
        openSession()

        assertEquals(1, userRepository.count(), "부를 때마다 계정이 생기면 옷장이 갈라진다")
    }

    @Test
    fun `화면에 나가는 이름은 설정값이다`() {
        val session = openSession()

        mockMvc.perform(get("/api/users/me").header("Authorization", session.bearer))
            .andExpect(status().isOk)
            // 코드 기본값이 아니라 test/application.yml 의 값이어야 설정을 읽은 것이다
            .andExpect(jsonPath("$.displayName").value("テスト主"))
    }

    @Test
    fun `자동으로 만든 계정의 비밀번호로는 아무도 로그인할 수 없다`() {
        openSession()
        val owner = requireNotNull(userRepository.findByEmail("owner@orbit.test"))

        // 빈 문자열이나 고정 문자열을 넣어 두면 그 값을 아는 사람에게 /api/auth/login 이 열린다
        assertTrue(owner.passwordHash.startsWith("\$2"), "해시가 아니면 안 된다: ${owner.passwordHash}")
        listOf("", " ", "orbit", "password", owner.email).forEach {
            assertTrue(!passwordEncoder.matches(it, owner.passwordHash), "'$it' 로 로그인이 되면 안 된다")
        }
    }

    // ── 계정 이어받기 ─────────────────────────────────────────────

    /**
     * 이 테스트가 이 파일에서 가장 중요하다.
     *
     * 실제 상황을 그대로 옮긴 것이다 — DB 에는 `existing@orbit.test`(옷이 든 계정)가
     * 있고, 설정 이메일은 `owner@orbit.test` 로 그와 다르다. 이메일 일치만 보는
     * 규칙이면 여기서 빈 계정을 새로 만들고 사용자는 옷장을 잃는다.
     */
    @Test
    fun `설정 이메일과 다르더라도 옷이 든 기존 계정을 이어받는다`() {
        val existing = existingUser("existing@orbit.test")
        val existingId = requireNotNull(existing.id)
        clothesOf(existingId, "흰 린넨 셔츠", MainCategory.TOP)
        clothesOf(existingId, "연청 데님", MainCategory.BOTTOM)

        val session = openSession()

        assertEquals(1, userRepository.count(), "계정을 새로 만들면 안 된다")
        mockMvc.perform(get("/api/clothes").header("Authorization", session.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[?(@.name == '흰 린넨 셔츠')]").exists())
    }

    @Test
    fun `계정이 여럿이면 데이터가 있는 쪽을 이어받는다`() {
        // 감사용으로 만들어 둔 빈 계정이 **먼저** 만들어져 있는 상황.
        // "가장 오래된 계정" 규칙이었다면 여기서 빈 계정을 골라 옷장을 잃는다.
        val audit = existingUser("audit@orbit.test", displayName = "감사")
        val real = existingUser("existing@orbit.test")
        val realId = requireNotNull(real.id)
        clothesOf(realId, "네이비 셔츠", MainCategory.TOP)

        val owner = ownerAccountService.resolveOrCreate()

        assertEquals(realId, owner.id, "옷이 있는 계정이 주인이다")
        assertNotEquals(audit.id, owner.id)
    }

    @Test
    fun `데이터가 없으면 설정 이메일과 같은 계정을 이어받는다`() {
        val other = existingUser("someone@orbit.test")
        val configured = existingUser("owner@orbit.test")

        val owner = ownerAccountService.resolveOrCreate()

        assertEquals(configured.id, owner.id, "흔적이 동점이면 설정이 가리키는 쪽이다")
        assertNotEquals(other.id, owner.id)
    }

    @Test
    fun `한 번 정해진 주인은 데이터가 움직여도 바뀌지 않는다`() {
        val first = existingUser("existing@orbit.test")
        clothesOf(requireNotNull(first.id), "네이비 셔츠", MainCategory.TOP)
        val owner = ownerAccountService.resolveOrCreate()

        // 나중에 만들어진 계정에 옷이 더 쌓여도(흔적 비교로는 이쪽이 이긴다)
        val newcomer = existingUser("newcomer@orbit.test")
        val newcomerId = requireNotNull(newcomer.id)
        repeat(5) { clothesOf(newcomerId, "옷 $it", MainCategory.TOP) }

        // 이미 표시가 남아 있으므로 계산을 다시 하지 않는다.
        assertEquals(owner.id, ownerAccountService.resolveOrCreate().id)
    }

    @Test
    fun `이어받은 계정의 이메일은 건드리지 않는다`() {
        val existing = existingUser("existing@orbit.test")
        clothesOf(requireNotNull(existing.id), "네이비 셔츠", MainCategory.TOP)

        val owner = ownerAccountService.resolveOrCreate()

        // 이메일은 식별자다. 화면에 나가지도 않는 값을 위해 계정을 옮길 이유가 없다.
        assertEquals("existing@orbit.test", owner.email)
        // 반대로 화면에 나가는 이름은 설정이 진실이라 설정값으로 맞춘다.
        assertEquals("テスト主", owner.displayName)
    }
}
