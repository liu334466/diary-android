package com.diary.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.diary.app.databinding.ActivityMainBinding
import com.diary.core.common.DiaryThemeMode
import com.diary.core.common.ThemeModeStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 Activity 宿主：底部三个一级页面常驻，编辑、阅读等二级页面全屏展示。
 * 主题在 setContentView 之前按用户偏好切换，切换后由调用方 recreate 生效。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var themeModeStore: ThemeModeStore

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(
            when (themeModeStore.resolved()) {
                DiaryThemeMode.DARK -> R.style.Theme_Diary_Dark
                DiaryThemeMode.PAPER -> R.style.Theme_Diary_Paper
                else -> R.style.Theme_Diary
            },
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost =
            supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val showTabs =
                destination.id == R.id.homeFragment ||
                    destination.id == R.id.categoryFragment ||
                    destination.id == R.id.settingsFragment
            binding.bottomNav.isVisible = showTabs
            binding.navDivider.isVisible = showTabs
        }
    }
}
