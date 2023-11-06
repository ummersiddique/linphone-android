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
package org.linphone.ui.main.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.getListOfSipAddressesAndPhoneNumbers
import org.linphone.core.Address
import org.linphone.core.Friend
import org.linphone.core.tools.Log
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressClickListener
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressModel
import org.linphone.ui.main.contacts.model.NumberOrAddressPickerDialogModel
import org.linphone.ui.main.history.adapter.ContactsAndSuggestionsListAdapter
import org.linphone.ui.main.history.model.ContactOrSuggestionModel
import org.linphone.ui.main.model.SelectedAddressModel
import org.linphone.ui.main.model.isInSecureMode
import org.linphone.ui.main.viewmodel.AddressSelectionViewModel
import org.linphone.utils.DialogUtils
import org.linphone.utils.RecyclerViewHeaderDecoration

@UiThread
abstract class GenericAddressPickerFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Generic Address Picker Fragment]"
    }

    private var numberOrAddressPickerDialog: Dialog? = null

    protected lateinit var adapter: ContactsAndSuggestionsListAdapter

    protected abstract val viewModel: AddressSelectionViewModel

    private val listener = object : ContactNumberOrAddressClickListener {
        @UiThread
        override fun onClicked(model: ContactNumberOrAddressModel) {
            val address = model.address
            coreContext.postOnCoreThread {
                if (address != null) {
                    Log.i(
                        "$TAG Selected address [${model.address.asStringUriOnly()}] from friend [${model.friend.name}]"
                    )
                    onAddressSelected(model.address, model.friend)
                }
            }

            numberOrAddressPickerDialog?.dismiss()
            numberOrAddressPickerDialog = null
        }

        @UiThread
        override fun onLongPress(model: ContactNumberOrAddressModel) {
        }
    }

    @WorkerThread
    abstract fun onSingleAddressSelected(address: Address, friend: Friend)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ContactsAndSuggestionsListAdapter(viewLifecycleOwner)

        adapter.contactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                handleClickOnContactModel(model)
            }
        }

        viewModel.searchFilter.observe(viewLifecycleOwner) { filter ->
            val trimmed = filter.trim()
            viewModel.applyFilter(trimmed)
        }
    }

    override fun onPause() {
        super.onPause()

        numberOrAddressPickerDialog?.dismiss()
        numberOrAddressPickerDialog = null
    }

    @UiThread
    protected fun setupRecyclerView(recyclerView: RecyclerView) {
        recyclerView.setHasFixedSize(true)
        recyclerView.adapter = adapter

        val headerItemDecoration = RecyclerViewHeaderDecoration(requireContext(), adapter, true)
        recyclerView.addItemDecoration(headerItemDecoration)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    @WorkerThread
    fun onAddressSelected(address: Address, friend: Friend) {
        if (viewModel.multipleSelectionMode.value == true) {
            val avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(address)
            val model = SelectedAddressModel(address, avatarModel) {
                viewModel.removeAddressModelFromSelection(it)
            }
            viewModel.addAddressModelToSelection(model)
        } else {
            onSingleAddressSelected(address, friend)
        }
    }

    protected fun handleClickOnContactModel(model: ContactOrSuggestionModel) {
        coreContext.postOnCoreThread { core ->
            val friend = model.friend
            if (friend == null) {
                Log.i("$TAG Friend is null, using address [${model.address}]")
                val fakeFriend = core.createFriend()
                fakeFriend.addAddress(model.address)
                onAddressSelected(model.address, fakeFriend)
                return@postOnCoreThread
            }

            val addressesCount = friend.addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = core.defaultAccount?.isInSecureMode() != true

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                val address = friend.addresses.first()
                Log.i("$TAG Only 1 SIP address found for contact [${friend.name}], using it")
                onAddressSelected(address, friend)
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = core.interpretUrl(number, true)
                if (address != null) {
                    Log.i("$TAG Only 1 phone number found for contact [${friend.name}], using it")
                    onAddressSelected(address, friend)
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            } else {
                val list = friend.getListOfSipAddressesAndPhoneNumbers(listener)
                Log.i(
                    "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                )

                coreContext.postOnMainThread {
                    val numberOrAddressModel = NumberOrAddressPickerDialogModel(list)
                    val dialog =
                        DialogUtils.getNumberOrAddressPickerDialog(
                            requireActivity(),
                            numberOrAddressModel
                        )
                    numberOrAddressPickerDialog = dialog

                    numberOrAddressModel.dismissEvent.observe(viewLifecycleOwner) { event ->
                        event.consume {
                            dialog.dismiss()
                        }
                    }

                    dialog.show()
                }
            }
        }
    }
}