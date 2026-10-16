package io.github.konove.notmytodo.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreRoot
import java.nio.file.Path

/** The store of this machine, one for the whole IDE: every open project shares its lock and its items. */
@Service(Service.Level.APP)
class StoreService {
    val store: Store = Store(System.getProperty(ROOT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: StoreRoot.default())

    companion object {
        /** Names another store, as the tests do: they must not write into the user's own. */
        const val ROOT_PROPERTY = "notmytodo.storeRoot"

        fun getInstance(): StoreService = service()
    }
}
