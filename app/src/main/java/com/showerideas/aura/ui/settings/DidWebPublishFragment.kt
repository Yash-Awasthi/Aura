package com.showerideas.aura.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.showerideas.aura.databinding.FragmentDidWebPublishBinding
import com.showerideas.aura.identity.DidWebPublisher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * R&D-D — Settings › Identity › Publish DID Document
 *
 * Allows the user to publish their AURA identity as a `did:web` DID Document
 * to any HTTPS domain they control. Once published, anyone can resolve their
 * DID at `https://<domain>/.well-known/did.json`.
 *
 * See: [DidWebPublisher]
 * See: ROADMAP §R&D-D
 */
@AndroidEntryPoint
class DidWebPublishFragment : Fragment() {

    @Inject lateinit var didWebPublisher: DidWebPublisher

    private var _binding: FragmentDidWebPublishBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDidWebPublishBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPublish.setOnClickListener { onPublishClicked() }
        binding.btnCopyDid.setOnClickListener  { onCopyDidClicked() }
    }

    private fun onPublishClicked() {
        val domain = binding.etDomain.text?.toString()?.trim() ?: ""

        if (!isValidDomain(domain)) {
            binding.tilDomain.error = "Enter a valid domain (e.g. yash.dev)"
            return
        }
        binding.tilDomain.error = null

        // Placeholder public key — in production, read from VcIssuer / identity key store
        val publicKeyHex = requireActivity()
            .getSharedPreferences("aura_identity", Context.MODE_PRIVATE)
            .getString("ec_pubkey_hex", null)

        if (publicKeyHex == null) {
            showStatus("No identity key found. Complete profile setup first.", isError = true)
            return
        }

        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = didWebPublisher.publish(domain, publicKeyHex)
            setLoading(false)
            result.onSuccess { did ->
                Timber.i("DidWebPublishFragment: published $did")
                binding.tvResultDid.text = did
                binding.btnCopyDid.visibility = View.VISIBLE
                showStatus("Published successfully", isError = false)
            }.onFailure { e ->
                Timber.e(e, "DidWebPublishFragment: publish failed")
                showStatus("Publish failed: ${e.message}", isError = true)
            }
        }
    }

    private fun onCopyDidClicked() {
        val did = binding.tvResultDid.text?.toString() ?: return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DID", did))
        Toast.makeText(requireContext(), "DID copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressPublish.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnPublish.isEnabled       = !loading
        binding.etDomain.isEnabled         = !loading
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.tvStatus.apply {
            text       = message
            visibility = View.VISIBLE
            setTextColor(
                if (isError) resources.getColor(android.R.color.holo_red_light, null)
                else         resources.getColor(android.R.color.holo_green_dark, null)
            )
        }
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        val clean = domain.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return Regex("^[a-zA-Z0-9][a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,}$").matches(clean)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
