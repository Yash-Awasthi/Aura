package com.showerideas.aura.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.showerideas.aura.BuildConfig
import com.showerideas.aura.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Task 87 — FIDO2 provider settings screen.
 *
 * Settings → Security → AURA as Password Provider.
 * Shows:
 *  - Enrolled passkeys: relying party, creation date, delete action
 *  - Hardware key relay toggle (only when [BuildConfig.ENABLE_HW_KEY_RELAY] = true)
 *
 * Navigation: SettingsFragment → FidoSettingsFragment
 * See: ROADMAP §Task 87
 */
@AndroidEntryPoint
class FidoSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_fido_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Hardware relay toggle — only show when enterprise feature flag enabled
        val hwRelaySection = view.findViewById<View>(R.id.sectionHwKeyRelay)
        hwRelaySection?.visibility = if (BuildConfig.ENABLE_HW_KEY_RELAY) View.VISIBLE else View.GONE
    }
}
