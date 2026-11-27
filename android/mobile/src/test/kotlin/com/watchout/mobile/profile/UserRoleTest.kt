package com.watchout.mobile.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UserRoleTest {

    @Test
    fun `Firestore 값을 역할로 바꾼다`() {
        assertEquals(UserRole.PATIENT, UserRole.fromFirestore("patient"))
        assertEquals(UserRole.GUARDIAN, UserRole.fromFirestore("guardian"))
    }

    @Test
    fun `값이 없거나 모르는 값이면 아직 안 고른 것으로 본다`() {
        assertNull(UserRole.fromFirestore(null))
        assertNull(UserRole.fromFirestore("admin"))
        assertNull(UserRole.fromFirestore("PATIENT")) // 대소문자도 정확히 맞아야 한다 (보안 규칙과 같게)
    }
}
