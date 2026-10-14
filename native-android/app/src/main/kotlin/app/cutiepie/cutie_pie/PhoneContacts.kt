package app.cutiepie.cutie_pie

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import app.cutiepie.cutie_pie.core.FriendlyContactId

/** Explicit plaintext export. Android's contact editor owns account/sync choice. */
object PhoneContacts {
    data class Entry(val name: String, val id: String)
    fun insert(name: String, id: String): Intent {
        require(FriendlyContactId.number(id) != null)
        val item = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Im.DATA, id)
            put(ContactsContract.CommonDataKinds.Im.PROTOCOL, ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM)
            put(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, "Cute Chat")
            put(ContactsContract.CommonDataKinds.Im.TYPE, ContactsContract.CommonDataKinds.Im.TYPE_OTHER)
        }
        return Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, name)
            .putExtra(ContactsContract.Intents.Insert.NOTES, "Cute Chat ID: $id\nApp contact ID; not a telephone number.")
            .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, arrayListOf(item))
    }
    // Opt-in READ_CONTACTS only. Do not read/upload unrelated phone numbers,
    // email addresses or the entire address book to the coordination service.
    fun read(context: Context): List<Entry> {
        val entries = linkedMapOf<String, Entry>()
        val columns = arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1)
        val selection = "(${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA6}=?) OR (${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA1} LIKE ?)"
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, columns, selection,
            arrayOf(ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE, "Cute Chat", ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "Cute Chat ID:%"), null)?.use { cursor ->
            var visited = 0
            while (cursor.moveToNext() && entries.size < 200 && visited++ < 500) {
                val stored = cursor.getString(1) ?: continue
                val id = if(stored.startsWith("Cute Chat ID:")) stored.substringAfter("Cute Chat ID:").lineSequence().first().trim() else stored
                val number = FriendlyContactId.number(id) ?: continue
                val name = (cursor.getString(0) ?: "Friend").take(80).filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }.ifBlank { "Friend" }
                entries[number] = Entry(name, id)
            }
        }
        return entries.values.toList()
    }
}
