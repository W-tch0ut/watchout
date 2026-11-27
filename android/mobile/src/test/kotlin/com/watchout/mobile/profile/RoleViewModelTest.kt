package com.watchout.mobile.profile

import com.watchout.mobile.MainDispatcherExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** Firestore 대신 쓰는 가짜 저장소. [roles]에 값을 흘려 "서버에서 역할이 왔다"를 흉내 낸다. */
private class FakeProfileRepository : ProfileRepository {
    val roles = MutableSharedFlow<UserRole?>(replay = 1)
    var failLoad = false
    var failSave = false
    val saved = mutableListOf<UserRole>()

    override fun observeRole(uid: String): Flow<UserRole?> =
        if (failLoad) flow { throw IllegalStateException("permission denied") } else roles

    override suspend fun setRole(uid: String, role: UserRole) {
        if (failSave) throw IllegalStateException("offline")
        saved += role
        roles.emit(role)
    }
}

@ExtendWith(MainDispatcherExtension::class)
class RoleViewModelTest {

    private val repo = FakeProfileRepository()
    private val vm by lazy { RoleViewModel("test-uid", repo) }

    @Test
    fun `서버 응답 전에는 불러오는 중이고 선택 화면을 보이지 않는다`() {
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.showSelect)
    }

    @Test
    fun `처음 로그인해서 역할이 없으면 선택 화면을 보이고 취소는 못 한다`() {
        vm // 구독 시작
        repo.roles.tryEmit(null)

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.showSelect)
        assertFalse(vm.state.value.canCancel)
    }

    @Test
    fun `저장된 역할이 있으면 바로 그 모드 화면으로 간다`() {
        vm
        repo.roles.tryEmit(UserRole.GUARDIAN)

        assertEquals(UserRole.GUARDIAN, vm.state.value.role)
        assertFalse(vm.state.value.showSelect)
    }

    @Test
    fun `모드를 고르면 저장하고 선택 화면을 닫는다`() {
        vm
        repo.roles.tryEmit(null)

        vm.choose(UserRole.PATIENT)

        assertEquals(listOf(UserRole.PATIENT), repo.saved)
        assertEquals(UserRole.PATIENT, vm.state.value.role)
        assertFalse(vm.state.value.showSelect)
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `모드 바꾸기를 누르면 선택 화면이 열리고 취소하면 원래 모드로 돌아간다`() {
        vm
        repo.roles.tryEmit(UserRole.PATIENT)

        vm.startChange()
        assertTrue(vm.state.value.showSelect)
        assertTrue(vm.state.value.canCancel)

        vm.cancelChange()
        assertFalse(vm.state.value.showSelect)
        assertEquals(UserRole.PATIENT, vm.state.value.role)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `같은 모드를 다시 고르면 저장하지 않고 닫기만 한다`() {
        vm
        repo.roles.tryEmit(UserRole.PATIENT)
        vm.startChange()

        vm.choose(UserRole.PATIENT)

        assertTrue(repo.saved.isEmpty())
        assertFalse(vm.state.value.showSelect)
    }

    @Test
    fun `저장에 실패하면 오류를 보이고 선택 화면에 머문다`() {
        repo.failSave = true
        vm
        repo.roles.tryEmit(null)

        vm.choose(UserRole.GUARDIAN)

        assertEquals(ProfileError.SAVE_FAILED, vm.state.value.error)
        assertNull(vm.state.value.role)
        assertTrue(vm.state.value.showSelect)
    }

    @Test
    fun `불러오기에 실패해도 앱이 꺼지지 않고 오류와 선택 화면을 보인다`() {
        repo.failLoad = true

        assertEquals(ProfileError.LOAD_FAILED, vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.showSelect)
    }
}
