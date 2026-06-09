package com.showerideas.aura.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.model.Contact
import timber.log.Timber
import java.util.UUID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    /**
     * favourites-only filter, driven by the new chip in the Contacts
     * fragment. When ON, the contacts flow switches to
     * [ContactRepository.favorites]; when OFF, it falls back to the
     * search-aware allContacts source.
     */
    private val _showFavouritesOnly = MutableStateFlow(false)
    val showFavouritesOnly: StateFlow<Boolean> = _showFavouritesOnly.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val contacts: StateFlow<List<Contact>> = combine(
        _searchQuery.debounce(200),
        _showFavouritesOnly
    ) { query, favsOnly -> query to favsOnly }
        .flatMapLatest { (query, favsOnly) ->
            when {
                favsOnly && query.isBlank() -> contactRepository.favorites
                favsOnly -> contactRepository.favorites.map { list ->
                    list.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                            it.email.contains(query, ignoreCase = true) ||
                            it.phone.contains(query, ignoreCase = true) ||
                            it.company.contains(query, ignoreCase = true) ||
                            it.title.contains(query, ignoreCase = true) ||
                            it.notes.contains(query, ignoreCase = true) ||
                            it.bio.contains(query, ignoreCase = true) ||
                            it.website.contains(query, ignoreCase = true)
                    }
                }
                query.isBlank() -> contactRepository.allContacts
                else -> contactRepository.search(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavouritesFilter() {
        _showFavouritesOnly.value = !_showFavouritesOnly.value
    }

    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            contactRepository.update(contact.copy(isFavorite = !contact.isFavorite))
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            contactRepository.delete(contact)
        }
    }

    /**
     * Save a contact decoded from an AURA share deeplink.
     *
     * [fields] is the map produced by [com.showerideas.aura.utils.DeeplinkUtils.decodeShareUrl].
     * Uses [ContactRepository.saveDeduped] so returning contacts merge instead of duplicate.
     */
    fun saveDeeplinkContact(fields: Map<String, String>) {
        viewModelScope.launch {
            val contact = Contact.fromMap(
                id         = UUID.randomUUID().toString(),
                map        = fields,
                endpointId = "deeplink"
            )
            Timber.i("ContactsViewModel: saving deeplink contact %s", contact.displayName)
            contactRepository.saveDeduped(contact)
        }
    }

    /**
     * Import a contact picked from the device address book (R&D-Q).
     *
     * Converts a [ContactPickerIntegration.PickedContact] to a [Contact] and
     * saves it via [ContactRepository.saveDeduped]. If the contact already exists
     * (matched by email or phone), the existing record is updated rather than duplicated.
     *
     * The contact source is tagged "address_book" to distinguish from AURA exchanges.
     */
    fun importFromAddressBook(picked: ContactPickerIntegration.PickedContact) {
        viewModelScope.launch {
            val fields = buildMap<String, String> {
                picked.displayName?.let { put("displayName", it) }
                picked.email?.let { put("email", it) }
                picked.phoneNumber?.let { put("phone", it) }
            }
            val contact = Contact.fromMap(
                id         = UUID.randomUUID().toString(),
                map        = fields,
                endpointId = "address_book"
            )
            Timber.i("ContactsViewModel: importing address book contact %s", contact.displayName)
            contactRepository.saveDeduped(contact)
        }
    }
}
