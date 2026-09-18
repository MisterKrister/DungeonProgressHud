package dev.krister.dungeonprogresshud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import net.fabricmc.loader.api.FabricLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class ProfileProvider(private val log: (String) -> Unit) {
    private companion object {
        const val PROFILE_FETCH_TIMEOUT_SECONDS = 30L
        const val SKYBLOCK_PV_MOD_ID = "skyblockpv"
        const val SKYBLOCKER_MOD_ID = "skyblocker"
        const val SKYBLOCK_PV_PROFILE_API = "me.owdding.skyblockpv.api.ProfileAPI"
        const val SKYBLOCKER_PROFILE_UTILS = "de.hysky.skyblocker.utils.ProfileUtils"
    }
    fun fetch(request: ProfileRequest): ProfileData {
        val failures = mutableListOf<String>()
        if (FabricLoader.getInstance().isModLoaded(SKYBLOCK_PV_MOD_ID)) {
            runCatching { fetchSkyBlockPvProfile(request) }
                .onSuccess { return it }
                .onFailure {
                    val message = profileFailureMessage(it)
                    failures += "SkyBlock Profile Viewer: $message"
                    log("SkyBlockPv profile fetch failed; trying SkyBlocker: $message")
                }
        }
        if (FabricLoader.getInstance().isModLoaded(SKYBLOCKER_MOD_ID)) {
            runCatching { fetchSkyBlockerProfile(request) }
                .onSuccess { return it }
                .onFailure {
                    val message = profileFailureMessage(it)
                    failures += "SkyBlocker: $message"
                    log("SkyBlocker profile fetch failed: $message")
                }
        }
        error(failures.ifEmpty { listOf("No profile provider loaded") }.joinToString("; "))
    }

    private fun fetchSkyBlockPvProfile(request: ProfileRequest): ProfileData {
        val gameProfile = GameProfile(request.playerUuid, request.playerName)
        log("Fetching SkyBlockPv profile user=${gameProfile.name} uuid=${gameProfile.id} sessionUuid=${request.playerUuid}")
        val profileApiClass = Class.forName(SKYBLOCK_PV_PROFILE_API)
        val profileApi = profileApiClass.getField("INSTANCE").get(null)
        val profileResult = CompletableFuture<List<*>>()
        val handler: (List<*>) -> Unit = { profileResult.complete(it) }
        val getProfiles = profileApiClass.getMethod("getProfiles", GameProfile::class.java,
            String::class.java, kotlin.Function1::class.java)

        getProfiles.invoke(
            profileApi,
            gameProfile,
            "dungeonprogresshud",
            handler,
        )

        val profiles = profileResult.get(PROFILE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val activeProfile = request.activeProfile
        log("SkyBlockPv returned ${profiles.size} profiles for uuid=${gameProfile.id}; active=${activeProfile?.second ?: "unknown"}/${activeProfile?.first ?: "unknown"}: ${profiles.joinToString { describeSkyBlockPvProfile(it) }}")
        val selected = profiles.firstOrNull { profile ->
            profile != null && invokeProfileMethod(profile, "getSelected") == true &&
                (activeProfile?.first == null || skyBlockPvProfileIdentity(profile)?.first == activeProfile.first)
        } ?: activeProfile?.let { (activeId, activeName) ->
            profiles.firstOrNull { profile ->
                val identity = skyBlockPvProfileIdentity(profile) ?: return@firstOrNull false
                (activeId != null && identity.first == activeId) ||
                    (activeName.isNotBlank() && identity.second.equals(activeName, true))
            }
        } ?: profiles.singleOrNull() ?: error(
            "No active SkyBlock profile match for ${gameProfile.name}; in-game profile is ${activeProfile?.second ?: "not loaded"}"
        )

        val profileId = invokeProfileMethod(selected, "getId") ?: error("Selected profile ID unavailable")
        val profileName = invokeProfileMethod(profileId, "getName") as? String ?: "Unknown"
        val backingProfile = invokeProfileMethod(selected, "getBackingProfile")
            ?: error("SkyBlock Profile Viewer backing profile unavailable")
        val dungeonFuture = invokeProfileMethod(backingProfile, "getDungeonData") as? CompletableFuture<*>
            ?: error("SkyBlock Profile Viewer dungeon request unavailable")
        val dungeonData = dungeonFuture.get(PROFILE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: error("Dungeon API unavailable")
        val dungeonTypes = invokeProfileMethod(dungeonData, "getDungeonTypes") as? Map<*, *>
            ?: error("Dungeon types unavailable")
        val catacombs = dungeonTypes["catacombs"] ?: error("Catacombs data unavailable")
        val catacombsExperience = (invokeProfileMethod(catacombs, "getExperience") as? Number)?.toLong()
            ?: error("Catacombs experience unavailable")

        log("Selected SkyBlockPv profile name=$profileName")
        return ProfileData(
            playerName = request.playerName,
            playerUuid = gameProfile.id.toString().replace("-", ""),
            profileName = profileName,
            profileId = skyBlockPvProfileIdentity(selected)?.first?.toString().orEmpty(),
            catacombsExperience = catacombsExperience,
        )
    }

    private fun fetchSkyBlockerProfile(request: ProfileRequest): ProfileData {
        val uuid = request.playerUuid.toString().replace("-", "")
        log("Fetching SkyBlocker profile user=${request.playerName} uuid=$uuid")
        val profileUtils = Class.forName(SKYBLOCKER_PROFILE_UTILS)
        val fetchProfiles = profileUtils.getMethod("fetchFullProfileByUuid", String::class.java)
        val future = fetchProfiles.invoke(null, uuid) as? CompletableFuture<*>
            ?: error("SkyBlocker profile request unavailable")
        val root = future.get(PROFILE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) as? JsonObject
            ?: error("SkyBlocker returned no profile data")
        if (root.get("success")?.asBoolean == false) error(root.get("cause")?.asString ?: "SkyBlocker profile request failed")
        val profiles = root.getAsJsonArray("profiles") ?: error("SkyBlocker returned no SkyBlock profiles")
        val selected = selectSkyBlockerProfile(profiles, request)
        val profileName = selected.get("cute_name")?.asString ?: "Unknown"
        val members = selected.getAsJsonObject("members") ?: error("SkyBlocker profile members unavailable")
        val member = members.getAsJsonObject(uuid)
            ?: members.getAsJsonObject(request.playerUuid.toString())
            ?: error("Selected profile missing player")
        val catacombsExperience = member.getAsJsonObject("dungeons")
            ?.getAsJsonObject("dungeon_types")
            ?.getAsJsonObject("catacombs")
            ?.get("experience")
            ?.asLong
            ?: error("Catacombs data unavailable")
        log("Selected SkyBlocker profile name=$profileName")
        return ProfileData(
            playerName = request.playerName,
            playerUuid = uuid,
            profileName = profileName,
            profileId = selected.get("profile_id")?.asString.orEmpty(),
            catacombsExperience = catacombsExperience,
        )
    }

    private fun selectSkyBlockerProfile(profiles: JsonArray, request: ProfileRequest): JsonObject {
        val candidates = profiles.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
        val activeProfile = request.activeProfile
        return candidates.firstOrNull { it.get("selected")?.asBoolean == true &&
            (activeProfile?.first == null || it.get("profile_id")?.asString?.replace("-", "") == activeProfile.first.toString().replace("-", "")) }
            ?: activeProfile?.let { (activeId, activeName) ->
                candidates.firstOrNull { profile ->
                    val profileId = profile.get("profile_id")?.asString?.let { value ->
                        runCatching { UUID.fromString(value) }.getOrNull()
                    }
                    val profileName = profile.get("cute_name")?.asString.orEmpty()
                    (activeId != null && profileId == activeId) ||
                        (activeName.isNotBlank() && profileName.equals(activeName, true))
                }
            }
            ?: candidates.singleOrNull()
            ?: error("No active SkyBlocker profile match for ${request.playerName}; in-game profile is ${activeProfile?.second ?: "not loaded"}")
    }

    private fun describeSkyBlockPvProfile(profile: Any?): String {
        if (profile == null) return "null"
        return runCatching {
            val identity = skyBlockPvProfileIdentity(profile)
            val name = identity?.second.orEmpty()
            val selected = invokeProfileMethod(profile, "getSelected") == true
            "${name.ifBlank { "unknown" }}(${identity?.first ?: "unknown"}, selected=$selected)"
        }.getOrDefault("unreadable")
    }

    private fun skyBlockPvProfileIdentity(profile: Any?): Pair<UUID?, String>? {
        if (profile == null) return null
        val id = invokeProfileMethod(profile, "getId") ?: return null
        return (invokeProfileMethod(id, "getId") as? UUID) to
            ((invokeProfileMethod(id, "getName") as? String).orEmpty())
    }

    private val methods = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, java.lang.reflect.Method>()
    private fun invokeProfileMethod(instance: Any, methodName: String): Any? =
        methods.computeIfAbsent(instance.javaClass to methodName) { (type, name) -> type.getMethod(name) }.invoke(instance)

    private fun profileFailureMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
