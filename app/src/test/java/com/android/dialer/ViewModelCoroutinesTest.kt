package com.android.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelCoroutinesTest {
    private interface TestRepository {
        suspend fun count(): Int
    }

    private class TestViewModel(
        private val repository: TestRepository,
    ) : ViewModel() {
        private val mutableCount = MutableStateFlow(0)
        val count: StateFlow<Int> = mutableCount.asStateFlow()

        fun refresh() {
            viewModelScope.launch { mutableCount.value = repository.count() }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshPublishesRepositoryCount() = runTest {
        val repository = mockk<TestRepository>()
        coEvery { repository.count() } returns 3
        val viewModel = TestViewModel(repository)

        viewModel.count.test {
            assertEquals(0, awaitItem())
            viewModel.refresh()
            assertEquals(3, awaitItem())
        }
    }
}
