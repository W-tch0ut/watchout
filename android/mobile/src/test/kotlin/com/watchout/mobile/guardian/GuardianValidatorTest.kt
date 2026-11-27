package com.watchout.mobile.guardian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GuardianValidatorTest {

    private fun input(
        name: String = "김가족",
        relation: String = "딸",
        phone: String = "010-0000-0000",
        id: String? = null,
    ) = Guardian(id = id, name = name, relation = relation, phone = phone)

    @Test
    fun `여러 모양의 휴대폰 번호를 숫자만 남겨 정리한다`() {
        assertEquals("01000000000", GuardianValidator.normalizePhone("010-0000-0000"))
        assertEquals("01000000000", GuardianValidator.normalizePhone("010 0000 0000"))
        assertEquals("01000000000", GuardianValidator.normalizePhone("+82 10-0000-0000"))
        assertEquals("01000000000", GuardianValidator.normalizePhone("+82 010-0000-0000")) // 0을 붙여 써도
        assertEquals("0110000000", GuardianValidator.normalizePhone("011-000-0000"))
    }

    @Test
    fun `휴대폰 번호가 아니면 null`() {
        assertNull(GuardianValidator.normalizePhone("02-000-0000")) // 집 전화
        assertNull(GuardianValidator.normalizePhone("010-0000")) // 너무 짧음
        assertNull(GuardianValidator.normalizePhone("119"))
        assertNull(GuardianValidator.normalizePhone(""))
    }

    @Test
    fun `화면에 보여줄 때는 하이픈을 넣는다`() {
        assertEquals("010-0000-0000", GuardianValidator.formatPhone("01000000000"))
        assertEquals("011-000-0000", GuardianValidator.formatPhone("0110000000"))
    }

    @Test
    fun `통과하면 공백을 지우고 번호를 정리한 값을 돌려준다`() {
        val v = GuardianValidator.validate(input(name = " 김가족 ", relation = " 딸 "), existingCount = 0)

        assertEquals(
            GuardianValidation.Valid(Guardian(id = null, name = "김가족", relation = "딸", phone = "01000000000")),
            v,
        )
    }

    @Test
    fun `이름, 관계, 번호가 잘못되면 이유를 알려준다`() {
        fun errorOf(g: Guardian) = (GuardianValidator.validate(g, 0) as GuardianValidation.Invalid).error

        assertEquals(GuardianError.EMPTY_NAME, errorOf(input(name = "  ")))
        assertEquals(GuardianError.NAME_TOO_LONG, errorOf(input(name = "가".repeat(21))))
        assertEquals(GuardianError.EMPTY_RELATION, errorOf(input(relation = "")))
        assertEquals(GuardianError.RELATION_TOO_LONG, errorOf(input(relation = "가".repeat(11))))
        assertEquals(GuardianError.INVALID_PHONE, errorOf(input(phone = "abc")))
    }

    @Test
    fun `새로 추가할 때만 최대 인원을 검사한다`() {
        val full = GuardianValidator.MAX_GUARDIANS

        assertEquals(
            GuardianValidation.Invalid(GuardianError.TOO_MANY),
            GuardianValidator.validate(input(), existingCount = full),
        )
        // 이미 있는 사람을 수정하는 것은 인원이 꽉 차도 된다
        val edit = GuardianValidator.validate(input(id = "g1"), existingCount = full)
        assertEquals(true, edit is GuardianValidation.Valid)
    }
}
