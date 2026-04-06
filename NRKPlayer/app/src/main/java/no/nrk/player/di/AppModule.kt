package no.nrk.player.di

import androidx.room.Room
import kotlinx.serialization.json.Json
import no.nrk.player.core.player.DigestEngine
import no.nrk.player.core.player.NrkPlayerController
import no.nrk.player.data.local.NrkDatabase
import no.nrk.player.data.remote.NrkApiService
import no.nrk.player.data.repository.NrkRepository
import no.nrk.player.ui.home.HomeViewModel
import no.nrk.player.ui.player.PlayerViewModel
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

val appModule = module {

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    single {
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    single {
        val json = Json {
            ignoreUnknownKeys  = true
            coerceInputValues  = true
            isLenient          = true
        }
        Retrofit.Builder()
            .baseUrl("https://psapi.nrk.no/")
            .client(get())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NrkApiService::class.java)
    }

    // ------------------------------------------------------------------
    // Database
    // ------------------------------------------------------------------

    single {
        Room.databaseBuilder(
            androidContext(),
            NrkDatabase::class.java,
            "nrk_player.db"
        ).build()
    }

    single { get<NrkDatabase>().watchProgressDao() }
    single { get<NrkDatabase>().digestCacheDao() }

    // ------------------------------------------------------------------
    // Repository
    // ------------------------------------------------------------------

    single { NrkRepository(api = get(), watchProgressDao = get()) }

    // ------------------------------------------------------------------
    // Player infrastructure
    // ------------------------------------------------------------------

    single { DigestEngine(context = androidContext()) }

    factory { params ->
        NrkPlayerController(
            context          = androidContext(),
            okHttpClient     = get(),
            progressCallback = params.get()
        )
    }

    // ------------------------------------------------------------------
    // ViewModels
    // ------------------------------------------------------------------

    viewModel { HomeViewModel(repository = get()) }

    viewModel { params ->
        PlayerViewModel(
            savedStateHandle  = params.get(),
            repository        = get(),
            playerController  = get { parametersOf(params.get<suspend (Long, Long) -> Unit>()) },
            digestEngine      = get()
        )
    }
}
