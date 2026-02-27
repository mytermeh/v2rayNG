package com.v2ray.ang.contracts

import android.view.View
import com.v2ray.ang.dto.ProfileItem

interface MainAdapterListener :BaseAdapterListener {

    fun onEdit(guid: String, position: Int, profile: ProfileItem)

    fun onSelectServer(guid: String)

    fun onShare(guid: String, profile: ProfileItem, position: Int, anchor: View)

}