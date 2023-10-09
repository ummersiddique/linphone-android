/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.MainActivityBinding
import org.linphone.ui.main.viewmodel.MainViewModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.slideInToastFromTop
import org.linphone.utils.slideInToastFromTopForDuration

@UiThread
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main Activity]"

        private const val CONTACTS_FRAGMENT_ID = 1
        private const val HISTORY_FRAGMENT_ID = 2
        private const val CHAT_FRAGMENT_ID = 3
        private const val MEETINGS_FRAGMENT_ID = 4
    }

    private lateinit var binding: MainActivityBinding

    private lateinit var viewModel: MainViewModel

    private lateinit var sharedViewModel: SharedMainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        while (!coreContext.isReady()) {
            Thread.sleep(20)
        }

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        }

        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)
        binding.lifecycleOwner = this

        viewModel = run {
            ViewModelProvider(this)[MainViewModel::class.java]
        }
        binding.viewModel = viewModel

        sharedViewModel = run {
            ViewModelProvider(this)[SharedMainViewModel::class.java]
        }

        viewModel.changeSystemTopBarColorEvent.observe(this) {
            it.consume { mode ->
                val color = when (mode) {
                    MainViewModel.IN_CALL -> AppUtils.getColor(R.color.green_success_500)
                    MainViewModel.ACCOUNT_REGISTRATION_FAILURE -> AppUtils.getColor(
                        R.color.red_danger_500
                    )
                    else -> AppUtils.getColor(R.color.orange_main_500)
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        delay(if (mode == MainViewModel.IN_CALL) 1000 else 0)
                        withContext(Dispatchers.Main) {
                            window.statusBarColor = color
                        }
                    }
                }
            }
        }

        viewModel.goBackToCallEvent.observe(this) {
            it.consume {
                coreContext.showCallActivity()
            }
        }

        viewModel.openDrawerEvent.observe(this) {
            it.consume {
                openDrawerMenu()
            }
        }

        viewModel.defaultAccountRegistrationErrorEvent.observe(this) {
            it.consume { error ->
                val tag = "DEFAULT_ACCOUNT_REGISTRATION_ERROR"
                if (error) {
                    // First remove any already existing connection error toat
                    removePersistentRedToast(tag)

                    val message = getString(R.string.toast_default_account_connection_state_error)
                    showPersistentRedToast(message, R.drawable.warning_circle, tag)
                } else {
                    removePersistentRedToast(tag)
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // TODO FIXME: uncomment
        // startActivity(Intent(this, WelcomeActivity::class.java))

        coreContext.greenToastToShowEvent.observe(this) {
            it.consume { pair ->
                val message = pair.first
                val icon = pair.second
                showGreenToast(message, icon)
            }
        }
    }

    override fun onStart() {
        coreContext.postOnCoreThread {
            val startDestination = when (corePreferences.defaultFragment) {
                CONTACTS_FRAGMENT_ID -> {
                    Log.i("$TAG Latest visited page is contacts, setting it as start destination")
                    R.id.contactsFragment
                }
                HISTORY_FRAGMENT_ID -> {
                    Log.i(
                        "$TAG Latest visited page is call history, setting it as start destination"
                    )
                    R.id.historyFragment
                }
                CHAT_FRAGMENT_ID -> {
                    Log.i(
                        "$TAG Latest visited page is conversations, setting it as start destination"
                    )
                    R.id.conversationsFragment
                }
                MEETINGS_FRAGMENT_ID -> {
                    Log.i("$TAG Latest visited page is meetings, setting it as start destination")
                    R.id.meetingsFragment
                }
                else -> { // Default
                    Log.i("$TAG No latest visited page stored, using default one (call history)")
                    R.id.historyFragment
                }
            }
            coreContext.postOnMainThread {
                val navGraph = findNavController().navInflater.inflate(R.navigation.main_nav_graph)
                navGraph.setStartDestination(startDestination)
                findNavController().setGraph(navGraph, null)
            }
        }

        super.onStart()
    }

    override fun onPause() {
        val defaultFragmentId = when (sharedViewModel.currentlyDisplayedFragment.value) {
            R.id.contactsFragment -> {
                CONTACTS_FRAGMENT_ID
            }
            R.id.historyFragment -> {
                HISTORY_FRAGMENT_ID
            }
            R.id.conversationsFragment -> {
                CHAT_FRAGMENT_ID
            }
            R.id.meetingsFragment -> {
                MEETINGS_FRAGMENT_ID
            }
            else -> { // Default
                HISTORY_FRAGMENT_ID
            }
        }
        coreContext.postOnCoreThread {
            Log.i("$TAG Storing default page [$defaultFragmentId]")
            corePreferences.defaultFragment = defaultFragmentId
            corePreferences.config.sync()
        }

        super.onPause()
    }

    @SuppressLint("RtlHardcoded")
    fun toggleDrawerMenu() {
        if (binding.drawerMenu.isDrawerOpen(Gravity.LEFT)) {
            closeDrawerMenu()
        } else {
            openDrawerMenu()
        }
    }

    fun closeDrawerMenu() {
        binding.drawerMenu.closeDrawer(binding.drawerMenuContent, true)
    }

    private fun openDrawerMenu() {
        binding.drawerMenu.openDrawer(binding.drawerMenuContent, true)
    }

    fun findNavController(): NavController {
        return findNavController(R.id.main_nav_host_fragment)
    }

    fun showGreenToast(message: String, @DrawableRes icon: Int) {
        val greenToast = AppUtils.getGreenToast(this, binding.toastsArea, message, icon)
        binding.toastsArea.addView(greenToast.root)

        greenToast.root.slideInToastFromTopForDuration(
            binding.toastsArea as ViewGroup,
            lifecycleScope
        )
    }

    fun showRedToast(message: String, @DrawableRes icon: Int) {
        val redToast = AppUtils.getRedToast(this, binding.toastsArea, message, icon)
        binding.toastsArea.addView(redToast.root)

        redToast.root.slideInToastFromTopForDuration(
            binding.toastsArea as ViewGroup,
            lifecycleScope
        )
    }

    private fun showPersistentRedToast(
        message: String,
        @DrawableRes icon: Int,
        tag: String,
        doNotTint: Boolean = false
    ) {
        val redToast = AppUtils.getRedToast(this, binding.toastsArea, message, icon, doNotTint)
        redToast.root.tag = tag
        binding.toastsArea.addView(redToast.root)

        redToast.root.slideInToastFromTop(
            binding.toastsArea as ViewGroup,
            true
        )
    }

    fun removePersistentRedToast(tag: String) {
        for (child in binding.toastsArea.children) {
            if (child.tag == tag) {
                binding.toastsArea.removeView(child)
            }
        }
    }

    private fun loadContacts() {
        coreContext.contactsManager.loadContacts(this)

        /* TODO: Uncomment later, only fixes a small UI display issue for contacts with emoji in the name
        val emojiCompat = coreContext.emojiCompat
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Wait for emoji compat library to have been loaded
                Log.i("[Main Activity] Waiting for emoji compat library to have been loaded")
                while (emojiCompat.loadState == EmojiCompat.LOAD_STATE_DEFAULT || emojiCompat.loadState == EmojiCompat.LOAD_STATE_LOADING) {
                    delay(100)
                }

                Log.i(
                    "[Main Activity] Emoji compat library loading status is ${emojiCompat.loadState}, re-loading contacts"
                )
                coreContext.postOnMainThread {
                    // Contacts loading must be started from UI thread
                    coreContext.contactsManager.loadContacts(this@MainActivity)
                }
            }
        }*/
    }
}
