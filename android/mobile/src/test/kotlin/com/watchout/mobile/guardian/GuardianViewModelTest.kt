package com.watchout.mobile.guardian

import com.watchout.mobile.MainDispatcherExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** Firestore 대신 메모리에 저장하는 가짜 저장소 */
private class FakeGuardianRepository : GuardianRepository {
    val data = MutableStateFlow<List<Guardian>>(emptyList())
    var failSave = false
    var failDelete = false
    val savedUids = mutableListOf<String>()
    private var nextId = 1

    override fun observe(uid: String): Flow<List<Guardian>> = data

    override suspend fun save(uid: String, guardian: Guardian) {
        if (failSave) error("network")
        savedUids += uid
        data.value = if (guardian.id == null) {
            data.value + guardian.copy(id = "g${nextId++}")
        } else {
            data.value.map { if (it.id == guardian.id) guardian else it }
        }
    }

    override suspend fun delete(uid: String, guardianId: String) {
        if (failDelete) error("network")
        data.value = data.value.filterNot { it.id == guardianId }
    }
}

@ExtendWith(MainDispatcherExtension::class)
class GuardianViewModelTest {

    private val repo = FakeGuardianRepository()
    // lazy: 테스트 시작 뒤(가짜 메인 디스패처를 끼운 뒤)에 만든다
    private val vm by lazy { GuardianViewModel(uid = "me", repo = repo) }

    private fun addGuardian(name: String, phone: String) {
        vm.startAdd()
        vm.onNameChange(name)
        vm.onRelationChange("딸")
        vm.onPhoneChange(phone)
        vm.save()
    }

    @Test
    fun `보호자를 추가하면 정리된 번호로 저장되고 창이 닫힌다`() {
        addGuardian("김가족", "010-0000-0001")

        assertEquals(listOf(Guardian("g1", "김가족", "딸", "01000000001")), vm.guardians.value)
        assertEquals(listOf("me"), repo.savedUids) // 로그인한 본인 위치에 저장
        assertNull(vm.editor.value)
    }

    @Test
    fun `수정 창은 기존 값을 하이픈 넣은 번호로 채우고, 저장하면 바뀐다`() {
        addGuardian("김가족", "01000000001")
        vm.startEdit(vm.guardians.value.single())
        assertEquals("010-0000-0001", vm.editor.value?.phone)

        vm.onPhoneChange("010-0000-0002")
        vm.save()

        assertEquals("01000000002", vm.guardians.value.single().phone)
        assertEquals(1, vm.guardians.value.size) // 새로 생기지 않고 수정됨
    }

    @Test
    fun `잘못된 번호는 저장하지 않고 창에 이유를 보여준다`() {
        addGuardian("김가족", "12345")

        assertEquals(GuardianError.INVALID_PHONE, vm.editor.value?.error)
        assertEquals(emptyList<Guardian>(), vm.guardians.value)
    }

    @Test
    fun `저장에 실패하면 창을 열어 둔 채 다시 시도할 수 있다`() {
        repo.failSave = true
        addGuardian("김가족", "01000000001")

        val editor = vm.editor.value
        assertNotNull(editor)
        assertEquals(GuardianError.SAVE_FAILED, editor?.error)
        assertEquals(false, editor?.isSaving)
    }

    @Test
    fun `최대 인원이 차면 추가 창을 열지 않는다`() {
        repeat(GuardianValidator.MAX_GUARDIANS) { addGuardian("가족$it", "0100000000$it") }

        vm.startAdd()

        assertNull(vm.editor.value)
        assertEquals(GuardianError.TOO_MANY, vm.listError.value)
    }

    @Test
    fun `삭제하면 목록에서 빠지고, 실패하면 안내한다`() {
        addGuardian("김가족", "01000000001")
        vm.delete("g1")
        assertEquals(emptyList<Guardian>(), vm.guardians.value)

        addGuardian("박가족", "01000000002")
        repo.failDelete = true
        vm.delete("g2")
        assertEquals(GuardianError.DELETE_FAILED, vm.listError.value)
    }

    @Test
    fun `목록을 불러오지 못하면 빈 목록과 안내를 보여준다`() {
        val broken = object : GuardianRepository by repo {
            override fun observe(uid: String): Flow<List<Guardian>> = flow { error("permission denied") }
        }
        val vm2 = GuardianViewModel("me", broken)

        assertEquals(emptyList<Guardian>(), vm2.guardians.value)
        assertEquals(GuardianError.LOAD_FAILED, vm2.listError.value)
    }
}
