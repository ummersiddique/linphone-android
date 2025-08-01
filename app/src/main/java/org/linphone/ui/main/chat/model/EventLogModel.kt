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
package org.linphone.ui.main.chat.model

import androidx.annotation.WorkerThread
import org.linphone.core.EventLog

class EventLogModel
    @WorkerThread
    constructor(
    val eventLog: EventLog,
    isFromGroup: Boolean = false,
    isGroupedWithPreviousOne: Boolean = false,
    isGroupedWithNextOne: Boolean = false,
    currentFilter: String = "",
    onContentClicked: ((fileModel: FileModel) -> Unit)? = null,
    onSipUriClicked: ((uri: String) -> Unit)? = null,
    onJoinConferenceClicked: ((uri: String) -> Unit)? = null,
    onWebUrlClicked: ((url: String) -> Unit)? = null,
    onContactClicked: ((friendRefKey: String) -> Unit)? = null,
    onRedToastToShow: ((pair: Pair<Int, Int>) -> Unit)? = null,
    onVoiceRecordingPlaybackEnded: ((id: String) -> Unit)? = null,
    onFileToExportToNativeGallery: ((path: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "[Event Log Model]"
    }

    val type: EventLog.Type = eventLog.type

    val isEvent = type != EventLog.Type.ConferenceChatMessage

    val model: Any = if (isEvent) {
        EventModel(eventLog)
    } else {
        val chatMessage = eventLog.chatMessage!!

        MessageModel(
            chatMessage,
            isFromGroup,
            isGroupedWithPreviousOne,
            isGroupedWithNextOne,
            currentFilter,
            onContentClicked,
            onSipUriClicked,
            onJoinConferenceClicked,
            onWebUrlClicked,
            onContactClicked,
            onRedToastToShow,
            onVoiceRecordingPlaybackEnded,
            onFileToExportToNativeGallery
        )
    }

    val notifyId = eventLog.notifyId

    @WorkerThread
    fun destroy() {
        (model as? MessageModel)?.destroy()
    }
}
