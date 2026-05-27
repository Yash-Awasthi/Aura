package com.showerideas.aura.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.showerideas.aura.fido.PasskeyDao
import com.showerideas.aura.fido.PasskeyEntity
import com.showerideas.aura.model.BlockedEndpoint
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeAuditEntry
import com.showerideas.aura.model.KnownPeer
import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.RoomMember
import com.showerideas.aura.model.RoomSession
import com.showerideas.aura.model.SharePreset

@Database(
    entities = [Contact::class, Profile::class, BlockedEndpoint::class, KnownPeer::class,
                ExchangeAuditEntry::class, SharePreset::class,
                RoomSession::class, RoomMember::class,
                PasskeyEntity::class],   // Task 83/84 — FIDO2 passkey metadata
    version = 12,  // v11→v12: passkeys table (T83) + gesture_zk_proof column (T91)
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
    abstract fun blockedEndpointDao(): BlockedEndpointDao
    abstract fun knownPeerDao(): KnownPeerDao
    abstract fun exchangeAuditDao(): ExchangeAuditDao
    abstract fun sharePresetDao(): SharePresetDao
    abstract fun roomSessionDao(): RoomSessionDao
    abstract fun passkeyDao(): PasskeyDao   // Task 84 — FIDO2 passkey metadata
}
