package pro.cslauncher.core.account

import kotlinx.serialization.Serializable
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class AccountType { MICROSOFT, OFFLINE, ELY_BY }

@Serializable
enum class MicrosoftAuthFlow { LEGACY_LIVE, AZURE_V2 }

/**
 * Non-secret account metadata. Tokens live **only** in a [SecretStore] (Android Keystore backed on
 * device), so this record can be written to disk, shown in the UI and included in bug reports without
 * leaking anything.
 */
@Serializable
data class Account(
    val id: String,
    val type: AccountType,
    val username: String,
    val uuid: String,
    val xuid: String? = null,
    val addedAtMs: Long = System.currentTimeMillis(),
    val accessTokenExpiresAtMs: Long? = null,
    val needsRelogin: Boolean = false,
    val skinUrl: String? = null,
    /** Null for old account files; new Microsoft logins record which endpoint minted the refresh token. */
    val microsoftAuthFlow: MicrosoftAuthFlow? = null
) {
    val isOffline: Boolean get() = type == AccountType.OFFLINE

    /** Expired, or about to be: refreshed a minute early so a launch never races the expiry. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isOffline) return false
        val exp = accessTokenExpiresAtMs ?: return false
        return nowMs >= exp - 60_000
    }

    val uuidWithDashes: String
        get() = when {
            uuid.length == 32 -> "${uuid.take(8)}-${uuid.substring(8, 12)}-${uuid.substring(12, 16)}-" +
                "${uuid.substring(16, 20)}-${uuid.drop(20)}"
            else -> uuid
        }

    fun describe(): String = when (type) {
        AccountType.OFFLINE -> "$username (offline)"
        AccountType.MICROSOFT -> username
        AccountType.ELY_BY -> "$username (ely.by)"
    }
}

/**
 * What the JVM needs to start a session. [accessToken] is the only secret here, and [toString] is
 * overridden so it can never end up in a log or a crash report by accident.
 */
data class LaunchSession(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val userType: String,
    val xuid: String,
    val clientId: String,
    val isOffline: Boolean
) {
    override fun toString(): String =
        "LaunchSession(username=$username, uuid=$uuid, userType=$userType, offline=$isOffline)"
}

class AuthException(message: String, val needsRelogin: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Offline identities. The UUID derivation matches what a vanilla server assigns an offline-mode
 * player, so worlds and server whitelists stay consistent between launches.
 */
object OfflineIdentity {

    private val NAME = Regex("^[A-Za-z0-9_]{3,16}$")

    fun validName(n: String): Boolean = NAME.matches(n)

    fun uuidFor(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    fun uuidString(name: String): String = uuidFor(name).toString().replace("-", "")

    fun session(name: String): LaunchSession = LaunchSession(
        username = name,
        uuid = uuidString(name),
        accessToken = "0",
        userType = "legacy",
        xuid = "",
        clientId = "",
        isOffline = true
    )

    fun validate(name: String): String? = when {
        name.isBlank() -> "Enter a player name"
        name.length < 3 -> "Player names need at least 3 characters"
        name.length > 16 -> "Player names are at most 16 characters"
        !validName(name) -> "Only letters, numbers and underscores are allowed"
        else -> null
    }
}

/** Storage for refresh tokens and access tokens. On Android this backs onto the Keystore. */
interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

class InMemorySecretStore : SecretStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun put(key: String, value: String) {
        map[key] = value
    }

    override fun get(key: String): String? = map[key]
    override fun remove(key: String) {
        map.remove(key)
    }
}

@Serializable
data class AccountsFile(
    val schemaVersion: Int = SCHEMA,
    val activeId: String? = null,
    val accounts: List<Account> = emptyList()
) {
    companion object {
        const val SCHEMA = 2

        fun migrate(f: AccountsFile): AccountsFile = if (f.schemaVersion >= SCHEMA) f else f.copy(schemaVersion = SCHEMA)
    }
}

/**
 * Account list plus the active account.
 *
 * Invariants this class protects, because breaking any of them produces confusing in-game behaviour:
 * * exactly one active account, or none when the list is empty;
 * * offline names are unique and valid, since two identical names in one session disconnects players;
 * * a removed account's secrets are removed from the [SecretStore] as well — an orphaned refresh
 *   token is a credential left on the device for no reason.
 */
class AccountStore(
    private val file: Path,
    private val secrets: SecretStore
) {
    private val lock = Any()

    private companion object {
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
    }

    fun load(): AccountsFile = synchronized(lock) {
        if (!Files.isRegularFile(file)) return AccountsFile()
        runCatching {
            AccountsFile.migrate(CsJson.decodeFromString(AccountsFile.serializer(), FsUtil.readText(file)))
        }.getOrElse { e ->
            CsLog.w("Accounts", "accounts.json unreadable, keeping a copy: ${e.message}")
            runCatching { Files.copy(file, file.resolveSibling("accounts.corrupt-${System.currentTimeMillis()}.json")) }
            AccountsFile()
        }
    }

    private fun save(f: AccountsFile) =
        FsUtil.writeAtomic(file, CsJson.encodeToString(AccountsFile.serializer(), f))

    fun accounts(): List<Account> = load().accounts

    fun active(): Account? = load().let { f -> f.accounts.firstOrNull { it.id == f.activeId } ?: f.accounts.firstOrNull() }

    fun add(account: Account): AccountsFile = synchronized(lock) {
        val f = load()
        require(f.accounts.none { it.id == account.id }) { "Account already exists" }
        if (account.type == AccountType.OFFLINE) {
            val problem = OfflineIdentity.validate(account.username)
            require(problem == null) { problem ?: "" }
            require(f.accounts.none { it.isOffline && it.username.equals(account.username, true) }) {
                "An offline account called '${account.username}' already exists"
            }
        }
        val next = f.copy(accounts = f.accounts + account, activeId = f.activeId ?: account.id)
        save(next)
        next
    }

    /** Creates an offline account and makes it active. The common path on a phone. */
    fun addOffline(name: String, active: Boolean = true): Account {
        OfflineIdentity.validate(name)?.let { throw AuthException(it) }
        return synchronized(lock) {
            // Two offline accounts with the same name share a UUID, so servers and worlds cannot
            // tell them apart: refuse instead of silently creating a broken duplicate.
            load().accounts.firstOrNull { it.isOffline && it.username.equals(name, true) }?.let {
                throw AuthException("An offline account called '${it.username}' already exists")
            }
            val account = Account(
                id = UUID.randomUUID().toString(),
                type = AccountType.OFFLINE,
                username = name,
                uuid = OfflineIdentity.uuidString(name),
                skinUrl = null
            )
            val f = load()
            val next = f.copy(accounts = f.accounts + account, activeId = if (active) account.id else f.activeId)
            save(next)
            account
        }
    }

    fun select(id: String): AccountsFile = synchronized(lock) {
        val f = load()
        require(f.accounts.any { it.id == id }) { "No such account" }
        val next = f.copy(activeId = id)
        save(next)
        next
    }

    fun remove(id: String): AccountsFile = synchronized(lock) {
        val f = load()
        secrets.remove("$ACCESS_TOKEN:$id")
        secrets.remove("$REFRESH_TOKEN:$id")
        val remaining = f.accounts.filterNot { it.id == id }
        val next = f.copy(
            accounts = remaining,
            activeId = f.activeId.takeIf { it != id } ?: remaining.firstOrNull()?.id
        )
        save(next)
        next
    }

    fun update(account: Account): AccountsFile = synchronized(lock) {
        val f = load()
        require(f.accounts.any { it.id == account.id }) { "No such account" }
        val next = f.copy(accounts = f.accounts.map { if (it.id == account.id) account else it })
        save(next)
        next
    }

    fun markNeedsRelogin(id: String, needs: Boolean = true) = synchronized(lock) {
        val f = load()
        val a = f.accounts.firstOrNull { it.id == id } ?: return@synchronized
        update(a.copy(needsRelogin = needs))
    }

    fun putTokens(id: String, accessToken: String, refreshToken: String?) {
        secrets.put("$ACCESS_TOKEN:$id", accessToken)
        if (refreshToken != null) secrets.put("$REFRESH_TOKEN:$id", refreshToken)
    }

    fun accessToken(id: String): String? = secrets.get("$ACCESS_TOKEN:$id")

    fun refreshToken(id: String): String? = secrets.get("$REFRESH_TOKEN:$id")

    /**
     * The session for [account]. Offline accounts never need a token; an online account without one is
     * reported as needing a re-login rather than launching with an empty token, which the game turns
     * into "Invalid session" *after* the loading screen.
     */
    fun session(account: Account): LaunchSession {
        if (account.isOffline) return OfflineIdentity.session(account.username)
        val token = accessToken(account.id)
            ?: throw AuthException("Sign in to '${account.username}' again", needsRelogin = true)
        return LaunchSession(
            username = account.username,
            uuid = account.uuid.replace("-", ""),
            accessToken = token,
            userType = "msa",
            xuid = account.xuid.orEmpty(),
            clientId = "",
            isOffline = false
        )
    }

    /** Never throws; returns null when there is no usable account. Used by the launch validator. */
    fun sessionOrNull(): LaunchSession? = runCatching { active()?.let { session(it) } }.getOrNull()
}
