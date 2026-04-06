package no.nrk.player.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// ---------------------------------------------------------------------------
// NRK PS API — public JSON endpoints used by nrk.no
// Base URL: https://psapi.nrk.no
// ---------------------------------------------------------------------------

interface NrkApiService {

    @GET("medium/tv/pages/{pageId}")
    suspend fun getPage(@Path("pageId") pageId: String): NrkPageResponse

    @GET("medium/tv/programme/{programId}")
    suspend fun getProgramme(@Path("programId") programId: String): NrkProgramme

    @GET("medium/tv/programme/{programId}/episodes")
    suspend fun getEpisodes(
        @Path("programId") programId: String,
        @Query("pageSize") pageSize: Int = 20,
        @Query("page") page: Int = 0
    ): NrkEpisodeListResponse

    @GET("playback/manifest/programme/{programId}")
    suspend fun getManifest(@Path("programId") programId: String): NrkManifest

    @GET("medium/tv/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 20
    ): NrkSearchResponse

    @GET("medium/tv/pages/frontpage")
    suspend fun getFrontpage(): NrkPageResponse

    @GET("medium/tv/programme/{programId}/recommendations")
    suspend fun getRecommendations(@Path("programId") programId: String): NrkRecommendationsResponse
}

// ---------------------------------------------------------------------------
// Response models
// ---------------------------------------------------------------------------

@Serializable
data class NrkPageResponse(
    val sections: List<NrkSection> = emptyList()
)

@Serializable
data class NrkSection(
    val id: String = "",
    val title: NrkTitle? = null,
    val plugs: List<NrkPlug> = emptyList(),
    @SerialName("_type") val type: String = ""
)

@Serializable
data class NrkTitle(val title: String = "")

@Serializable
data class NrkPlug(
    val id: String = "",
    val title: String = "",
    val tagline: String? = null,
    val image: NrkImage? = null,
    @SerialName("_type") val type: String = "",
    val targetType: String? = null,
    val duration: NrkDuration? = null,
    val availability: NrkAvailability? = null
)

@Serializable
data class NrkImage(
    val webImages: List<NrkWebImage> = emptyList()
) {
    fun url(width: Int = 800): String =
        webImages.firstOrNull()?.uri
            ?.replace("{width}", width.toString())
            ?: ""
}

@Serializable
data class NrkWebImage(
    val uri: String = "",
    val width: Int = 0,
    val pixelWidth: Int = 0
)

@Serializable
data class NrkDuration(
    val seconds: Long = 0,
    val iso8601: String = ""
)

@Serializable
data class NrkAvailability(
    val status: String = "",
    val isGeoBlocked: Boolean = false
)

@Serializable
data class NrkProgramme(
    val programId: String = "",
    val title: String = "",
    val description: String? = null,
    val image: NrkImage? = null,
    val category: NrkCategory? = null,
    val duration: NrkDuration? = null,
    val availability: NrkAvailability? = null,
    val contributors: List<NrkContributor> = emptyList(),
    val productionYear: Int? = null
)

@Serializable
data class NrkCategory(
    val id: String = "",
    val name: String = ""
)

@Serializable
data class NrkContributor(
    val role: String = "",
    val name: String = ""
)

@Serializable
data class NrkEpisodeListResponse(
    val episodes: List<NrkPlug> = emptyList(),
    val totalCount: Int = 0
)

@Serializable
data class NrkManifest(
    val playable: NrkPlayable? = null,
    val nonPlayable: NrkNonPlayable? = null
)

@Serializable
data class NrkPlayable(
    val assets: List<NrkAsset> = emptyList(),
    val subtitles: List<NrkSubtitle> = emptyList(),
    val duration: NrkDuration? = null,
    val endSequenceStartTime: String? = null   // ISO-8601 offset for credits detection
)

@Serializable
data class NrkAsset(
    val url: String = "",
    val format: String = "",
    val mimeType: String = ""
)

@Serializable
data class NrkSubtitle(
    val language: String = "",
    val label: String = "",
    val type: String = "",                     // "normal" | "hardOfHearing" | "foreign"
    val defaultOn: Boolean = false,
    val url: String = ""
)

@Serializable
data class NrkNonPlayable(val reason: String = "")

@Serializable
data class NrkSearchResponse(
    val hits: List<NrkSearchHit> = emptyList(),
    val totalHits: Int = 0
)

@Serializable
data class NrkSearchHit(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val image: NrkImage? = null,
    @SerialName("_type") val type: String = ""
)

@Serializable
data class NrkRecommendationsResponse(
    val programmes: List<NrkPlug> = emptyList()
)
